-- P1-06 roles, authoritative submitter identity and append-only audit history.
--
-- P1-05 recorded review decisions with a client-supplied, unauthenticated
-- `decided_by` placeholder. P1-06 binds every write to the authenticated
-- principal. The actor itself is not stored per case for review decisions (that
-- stays in review_decision), but the invoice case must remember who submitted
-- it so self-approval can be rejected and a SUBMITTER can only see their cases.
--
-- 1. invoice_case.submitted_by is the authoritative creator identity. It is
--    backfilled for pre-P1-06 rows with the explicit `legacy` sentinel because
--    no authenticated identity existed before this migration. It is immutable:
--    a case does not change hands.
-- 2. audit_entry is the append-only business audit log. One row is written in
--    the same transaction as each audited business mutation; the V1
--    reject_immutable_change trigger rejects UPDATE and DELETE at the database.
--    before_state/after_state are structured JSON, never raw input documents,
--    credentials or hidden model reasoning.

ALTER TABLE invoice_case
    ADD COLUMN submitted_by varchar(64);

-- Pre-P1-06 rows never had an authenticated creator. The sentinel is a normal
-- value of the same bounded shape, documented as "not attributable".
UPDATE invoice_case
   SET submitted_by = 'legacy'
 WHERE submitted_by IS NULL;

ALTER TABLE invoice_case
    ALTER COLUMN submitted_by SET NOT NULL,
    ADD CONSTRAINT ck_invoice_case_submitted_by CHECK (btrim(submitted_by) <> '');

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
        'CASE_REJECTED',
        'APPROVE')),
    CONSTRAINT ck_audit_entry_target_type CHECK (target_type IN (
        'CASE',
        'DRAFT_REVISION',
        'EVIDENCE_BUNDLE',
        'MATCH_RESULT',
        'REVIEW_SNAPSHOT',
        'REVIEW_DECISION')),
    CONSTRAINT ck_audit_entry_business_version CHECK (business_version >= 0),
    CONSTRAINT ck_audit_entry_trace CHECK (btrim(trace_id) <> ''),
    -- When the audit target is the case itself, the target id must be that
    -- case. This is the declarative same-case/target integrity that is feasible
    -- for a free-form target id.
    CONSTRAINT ck_audit_entry_case_target CHECK (
        (target_type <> 'CASE') OR (target_id = invoice_case_id::text))
);

CREATE INDEX ix_audit_entry_case_cursor
    ON audit_entry (invoice_case_id, occurred_at DESC, id DESC);

CREATE INDEX ix_audit_entry_cursor
    ON audit_entry (occurred_at DESC, id DESC);

CREATE TRIGGER trg_audit_entry_immutable
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
