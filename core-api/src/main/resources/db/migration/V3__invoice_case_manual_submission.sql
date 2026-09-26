-- P1-03 manual invoice submission and request-id idempotency.
--
-- The draft, evidence bundle, version and hash columns already exist from the
-- P1-01 baseline. This migration adds the database-level guards submission
-- needs:
--
-- 1. An idempotency record keyed by (operation scope, aggregate/resource,
--    request id) with a unique constraint so concurrent identical requests race
--    safely. A deferred constraint trigger rejects any committed record that is
--    missing its stored response, so a reservation left incomplete by a bug can
--    never be observed as a completed request.
-- 2. A trigger that makes the invoice lines of a sealed draft revision
--    immutable, including UPDATEs that try to move a line into or out of a
--    sealed revision.
-- 3. A trigger that allows a draft revision to be sealed exactly once and makes
--    an already SEALED revision immutable, independent of the EvidenceBundle
--    foreign key.

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY,
    scope varchar(64) NOT NULL,
    resource_key varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    request_hash varchar(128) NOT NULL,
    response_status integer,
    response_body text,
    created_at timestamptz NOT NULL,
    CONSTRAINT ux_idempotency_request UNIQUE (scope, resource_key, request_id)
);

-- A reservation is inserted with a NULL response while the side effect runs and
-- is filled in before commit. The deferred constraint trigger evaluates the
-- final row state at commit, so a transaction may temporarily hold an
-- incomplete row but can never commit one.
CREATE OR REPLACE FUNCTION require_complete_idempotency_record() RETURNS trigger AS $$
DECLARE
    stored_status integer;
    stored_body text;
BEGIN
    SELECT response_status, response_body INTO stored_status, stored_body
        FROM idempotency_record WHERE id = NEW.id;
    IF stored_status IS NULL OR stored_body IS NULL THEN
        RAISE EXCEPTION 'idempotency_record % is missing a stored response at commit', NEW.id
            USING ERRCODE = '23000';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_idempotency_record_complete
    AFTER INSERT OR UPDATE ON idempotency_record
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_complete_idempotency_record();

-- A draft revision is only editable while OPEN. Once it is SEALED its invoice
-- lines are part of an immutable EvidenceBundle version and must never change.
-- On UPDATE both the old and the new target revision are checked, so a line
-- cannot be moved from a sealed revision to an open one or vice versa.
CREATE OR REPLACE FUNCTION reject_sealed_revision_line_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (SELECT 1 FROM draft_revision dr
                   WHERE dr.id = NEW.draft_revision_id AND dr.status = 'SEALED') THEN
            RAISE EXCEPTION 'invoice_line rows of a sealed draft revision are immutable'
                USING ERRCODE = '23000';
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM draft_revision dr
                   WHERE dr.id = OLD.draft_revision_id AND dr.status = 'SEALED') THEN
            RAISE EXCEPTION 'invoice_line rows of a sealed draft revision are immutable'
                USING ERRCODE = '23000';
        END IF;
        RETURN OLD;
    ELSE
        IF EXISTS (SELECT 1 FROM draft_revision dr
                   WHERE dr.id IN (OLD.draft_revision_id, NEW.draft_revision_id)
                     AND dr.status = 'SEALED') THEN
            RAISE EXCEPTION 'invoice_line rows of a sealed draft revision are immutable'
                USING ERRCODE = '23000';
        END IF;
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_line_sealed_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON invoice_line
    FOR EACH ROW EXECUTE FUNCTION reject_sealed_revision_line_change();

-- A draft revision may transition OPEN -> SEALED exactly once. Any change to an
-- already SEALED revision, and any DELETE of one, is rejected at the database
-- so the frozen source of an EvidenceBundle can never be rewritten.
CREATE OR REPLACE FUNCTION guard_sealed_draft_revision() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'SEALED' THEN
            RAISE EXCEPTION 'sealed draft revision % is immutable', OLD.id
                USING ERRCODE = '23000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'SEALED' THEN
        RAISE EXCEPTION 'sealed draft revision % is immutable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.status NOT IN ('OPEN', 'SEALED') THEN
        RAISE EXCEPTION 'invalid draft revision status %', NEW.status
            USING ERRCODE = '23000';
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'SEALED' AND NEW.sealed_at IS NULL THEN
        RAISE EXCEPTION 'sealing draft revision % requires sealed_at', OLD.id
            USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_draft_revision_sealed_immutable
    BEFORE UPDATE OR DELETE ON draft_revision
    FOR EACH ROW EXECUTE FUNCTION guard_sealed_draft_revision();
