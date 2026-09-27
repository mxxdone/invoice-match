-- P1-06 roles, authoritative submitter identity and append-only audit history.
--
-- P1-05 recorded review decisions with a client-supplied, unauthenticated
-- `decided_by` placeholder. P1-06 binds every write to the authenticated
-- principal. This migration provides:
--
-- 1. invoice_case.submitted_by: the authoritative creator identity. It is
--    immutable after insert and pre-P1-06 rows are backfilled with a reserved
--    sentinel (`__reserved__`) that the application refuses to accept as a
--    configured login.
-- 2. idempotency_record.actor: the request idempotency key is namespaced by the
--    authenticated principal so one principal can never replay or leak another
--    principal's stored response. Existing rows are backfilled with the same
--    reserved sentinel.
-- 3. audit_entry: the append-only business audit log, written in the same
--    transaction as the business mutation. The database validates the target
--    relationship, the actor roles and the business version; it does not verify
--    the actor's identity, which is a server-side application trust boundary.
--
-- The V1 reject_immutable_change trigger already exists and is reused.

-- ---------------------------------------------------------------------------
-- 1. Authoritative, immutable submitter identity
-- ---------------------------------------------------------------------------

ALTER TABLE invoice_case
    ADD COLUMN submitted_by varchar(64);

-- Pre-P1-06 rows never had an authenticated creator; the reserved sentinel is
-- impossible to log in as and is validated against the configured demo users.
UPDATE invoice_case
   SET submitted_by = '__reserved__'
 WHERE submitted_by IS NULL;

ALTER TABLE invoice_case
    ALTER COLUMN submitted_by SET NOT NULL,
    ADD CONSTRAINT ck_invoice_case_submitted_by CHECK (btrim(submitted_by) <> '');

-- A case never changes hands: submitted_by is authoritative and immutable.
CREATE OR REPLACE FUNCTION guard_invoice_case_submitted_by() RETURNS trigger AS $$
BEGIN
    IF NEW.submitted_by IS DISTINCT FROM OLD.submitted_by THEN
        RAISE EXCEPTION 'invoice_case.submitted_by is immutable (case %)', OLD.id
            USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_case_submitted_by_immutable
    BEFORE UPDATE ON invoice_case
    FOR EACH ROW EXECUTE FUNCTION guard_invoice_case_submitted_by();

-- ---------------------------------------------------------------------------
-- 2. Principal-namespaced idempotency
-- ---------------------------------------------------------------------------

ALTER TABLE idempotency_record
    ADD COLUMN actor varchar(64);

-- A pre-P1-06 reservation cannot be attributed to an authenticated actor, so it
-- is backfilled with the reserved sentinel and can never be replayed by a login.
UPDATE idempotency_record
   SET actor = '__reserved__'
 WHERE actor IS NULL;

ALTER TABLE idempotency_record
    ALTER COLUMN actor SET NOT NULL,
    DROP CONSTRAINT ux_idempotency_request,
    ADD CONSTRAINT ux_idempotency_request UNIQUE (scope, resource_key, actor, request_id),
    ADD CONSTRAINT ck_idempotency_actor CHECK (btrim(actor) <> '');

-- ---------------------------------------------------------------------------
-- 3. Append-only audit history
-- ---------------------------------------------------------------------------

CREATE TABLE audit_entry (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    occurred_at timestamptz NOT NULL,
    actor varchar(64) NOT NULL,
    actor_roles varchar(255) NOT NULL,
    action varchar(64) NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id varchar(128) NOT NULL,
    business_version bigint NOT NULL,
    before_state jsonb,
    after_state jsonb,
    request_id varchar(128),
    trace_id varchar(64) NOT NULL,
    CONSTRAINT ck_audit_entry_actor CHECK (btrim(actor) <> ''),
    CONSTRAINT ck_audit_entry_action CHECK (action IN (
        'CASE_CREATED',
        'DRAFT_LINES_REPLACED',
        'CASE_SUBMITTED',
        'SUPPLEMENT_REVISION_OPENED',
        'MATCH_RUN',
        'REVIEW_SNAPSHOT_FROZEN',
        'ITEM_MAPPED',
        'SUPPLEMENT_REQUESTED',
        'CASE_REJECTED')),
    CONSTRAINT ck_audit_entry_target_type CHECK (target_type IN (
        'CASE',
        'DRAFT_REVISION',
        'EVIDENCE_BUNDLE',
        'MATCH_RESULT',
        'REVIEW_SNAPSHOT',
        'REVIEW_DECISION')),
    CONSTRAINT ck_audit_entry_business_version CHECK (business_version >= 0),
    CONSTRAINT ck_audit_entry_trace CHECK (btrim(trace_id) <> ''),
    -- When the audit target is the case itself, the target id must be that case.
    CONSTRAINT ck_audit_entry_case_target CHECK (
        (target_type <> 'CASE') OR (target_id = invoice_case_id::text))
);

-- The database validates the semantic relationships the application asserts:
-- the typed target exists and belongs to the same case, the actor roles are a
-- canonical subset of the three business roles, and the recorded business
-- version matches the case version visible in the same transaction. It does NOT
-- verify that the actor really is that principal: authentication is an
-- application trust boundary.
CREATE OR REPLACE FUNCTION validate_audit_entry() RETURNS trigger AS $$
DECLARE
    parts text[];
    canon text;
    distinct_count integer;
    current_version bigint;
    target_found integer;
BEGIN
    -- Actor roles must be a non-empty, duplicate-free, canonical subset.
    parts := string_to_array(NEW.actor_roles, ',');
    IF array_length(parts, 1) IS NULL THEN
        RAISE EXCEPTION 'audit actor_roles must not be empty'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(parts) AS p
               WHERE p NOT IN ('SUBMITTER', 'APPROVER', 'OPERATOR')) THEN
        RAISE EXCEPTION 'audit actor_roles contains an unknown role: %', NEW.actor_roles
            USING ERRCODE = '23514';
    END IF;
    SELECT count(DISTINCT p) INTO distinct_count FROM unnest(parts) AS p;
    IF distinct_count <> array_length(parts, 1) THEN
        RAISE EXCEPTION 'audit actor_roles contains duplicates: %', NEW.actor_roles
            USING ERRCODE = '23514';
    END IF;
    SELECT string_agg(p, ',' ORDER BY array_position(ARRAY['SUBMITTER', 'APPROVER', 'OPERATOR'], p))
      INTO canon FROM unnest(parts) AS p;
    IF canon <> NEW.actor_roles THEN
        RAISE EXCEPTION 'audit actor_roles is not canonical: %', NEW.actor_roles
            USING ERRCODE = '23514';
    END IF;

    -- Business version must match the case version at insertion. Historical
    -- rows are untouched by later version changes because this runs only on
    -- INSERT.
    SELECT version INTO current_version FROM invoice_case WHERE id = NEW.invoice_case_id;
    IF current_version IS NULL THEN
        RAISE EXCEPTION 'audit entry references a missing case %', NEW.invoice_case_id
            USING ERRCODE = '23503';
    END IF;
    IF NEW.business_version <> current_version THEN
        RAISE EXCEPTION 'audit business_version % does not match case version %',
            NEW.business_version, current_version
            USING ERRCODE = '23514';
    END IF;

    -- The typed target must exist and belong to the same case.
    IF NEW.target_type = 'CASE' THEN
        IF NEW.target_id <> NEW.invoice_case_id::text THEN
            RAISE EXCEPTION 'audit CASE target % is not case %', NEW.target_id, NEW.invoice_case_id
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.target_type = 'DRAFT_REVISION' THEN
        SELECT count(*) INTO target_found FROM draft_revision
         WHERE id = NEW.target_id::uuid AND invoice_case_id = NEW.invoice_case_id;
        IF target_found = 0 THEN
            RAISE EXCEPTION 'audit draft revision % does not belong to case %',
                NEW.target_id, NEW.invoice_case_id USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.target_type = 'EVIDENCE_BUNDLE' THEN
        SELECT count(*) INTO target_found FROM evidence_bundle
         WHERE id = NEW.target_id::uuid AND invoice_case_id = NEW.invoice_case_id;
        IF target_found = 0 THEN
            RAISE EXCEPTION 'audit evidence bundle % does not belong to case %',
                NEW.target_id, NEW.invoice_case_id USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.target_type = 'MATCH_RESULT' THEN
        SELECT count(*) INTO target_found FROM match_result
         WHERE id = NEW.target_id::uuid AND invoice_case_id = NEW.invoice_case_id;
        IF target_found = 0 THEN
            RAISE EXCEPTION 'audit match result % does not belong to case %',
                NEW.target_id, NEW.invoice_case_id USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.target_type = 'REVIEW_SNAPSHOT' THEN
        SELECT count(*) INTO target_found FROM review_snapshot
         WHERE id = NEW.target_id::uuid AND invoice_case_id = NEW.invoice_case_id;
        IF target_found = 0 THEN
            RAISE EXCEPTION 'audit review snapshot % does not belong to case %',
                NEW.target_id, NEW.invoice_case_id USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.target_type = 'REVIEW_DECISION' THEN
        SELECT count(*) INTO target_found FROM review_decision
         WHERE id = NEW.target_id::uuid AND invoice_case_id = NEW.invoice_case_id;
        IF target_found = 0 THEN
            RAISE EXCEPTION 'audit review decision % does not belong to case %',
                NEW.target_id, NEW.invoice_case_id USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'audit target type % is not supported', NEW.target_type
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_entry_validate
    BEFORE INSERT ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION validate_audit_entry();

CREATE TRIGGER trg_audit_entry_immutable
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE INDEX ix_audit_entry_case_cursor
    ON audit_entry (invoice_case_id, occurred_at DESC, id DESC);

CREATE INDEX ix_audit_entry_cursor
    ON audit_entry (occurred_at DESC, id DESC);
