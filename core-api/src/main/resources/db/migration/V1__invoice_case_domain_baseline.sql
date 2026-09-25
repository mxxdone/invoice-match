-- P1-01 domain contract and PostgreSQL baseline.
-- Whole-won KRW amounts are bigint, quantities are positive integers, and all
-- timestamps are timezone-aware (UTC assumed by application configuration).

CREATE TABLE invoice_case (
    id uuid PRIMARY KEY,
    supplier_id varchar(64) NOT NULL,
    purchase_order_id varchar(64) NOT NULL,
    invoice_number varchar(100) NOT NULL,
    normalized_invoice_number varchar(100) NOT NULL,
    status varchar(32) NOT NULL,
    current_draft_revision_id uuid,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_at timestamptz,
    CONSTRAINT ck_invoice_case_status CHECK (status IN (
        'DRAFT',
        'SUBMITTED',
        'REVIEW_PENDING',
        'SUPPLEMENT_REQUIRED',
        'REJECTED',
        'EXPORT_PENDING',
        'EXPORTED')),
    CONSTRAINT ck_invoice_case_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE draft_revision (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    revision_number integer NOT NULL,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    sealed_at timestamptz,
    CONSTRAINT ck_draft_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_draft_revision_status CHECK (status IN ('OPEN', 'SEALED')),
    CONSTRAINT ck_draft_revision_sealed CHECK (
        (status = 'OPEN' AND sealed_at IS NULL)
        OR (status = 'SEALED' AND sealed_at IS NOT NULL)),
    CONSTRAINT ux_draft_revision_number UNIQUE (invoice_case_id, revision_number)
);

-- Only one draft revision may be edited at a time for a claim.
CREATE UNIQUE INDEX ux_draft_revision_one_open
    ON draft_revision (invoice_case_id)
    WHERE status = 'OPEN';

CREATE TABLE invoice_line (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    draft_revision_id uuid NOT NULL REFERENCES draft_revision (id),
    line_number integer NOT NULL,
    raw_item_name varchar(500) NOT NULL,
    quantity integer NOT NULL,
    unit_price bigint NOT NULL,
    confirmed_item_id varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_invoice_line_number CHECK (line_number > 0),
    CONSTRAINT ck_invoice_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_invoice_line_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ux_invoice_line_number UNIQUE (draft_revision_id, line_number)
);

CREATE TABLE evidence_bundle (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    version_number integer NOT NULL,
    payload_hash varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    submitted_at timestamptz NOT NULL,
    CONSTRAINT ck_evidence_bundle_version CHECK (version_number > 0),
    CONSTRAINT ux_evidence_bundle_version UNIQUE (invoice_case_id, version_number)
);

CREATE TABLE match_result (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    evidence_bundle_id uuid REFERENCES evidence_bundle (id),
    result_hash varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE review_snapshot (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    evidence_bundle_id uuid NOT NULL REFERENCES evidence_bundle (id),
    match_result_id uuid REFERENCES match_result (id),
    target_case_version bigint NOT NULL,
    target_evidence_bundle_version integer NOT NULL,
    payload_hash varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_review_snapshot_case_version CHECK (target_case_version >= 0),
    CONSTRAINT ck_review_snapshot_bundle_version CHECK (target_evidence_bundle_version > 0)
);

CREATE TABLE review_decision (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case (id),
    review_snapshot_id uuid NOT NULL REFERENCES review_snapshot (id),
    decision varchar(32) NOT NULL,
    decided_by varchar(64) NOT NULL,
    reason varchar(1000),
    decision_payload jsonb,
    payload_hash varchar(128) NOT NULL,
    decided_at timestamptz NOT NULL,
    CONSTRAINT ck_review_decision_type CHECK (
        decision IN ('MAPPING', 'SUPPLEMENT_REQUESTED', 'REJECTED', 'APPROVED'))
);

-- Submitted evidence versions, deterministic results, approval snapshots and
-- human decisions are append-only. Application code creates a new row instead
-- of mutating an existing one; this trigger protects the invariant at the DB.
CREATE OR REPLACE FUNCTION reject_immutable_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only and does not allow %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'raise_exception';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_evidence_bundle_immutable
    BEFORE UPDATE ON evidence_bundle
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_update();

CREATE TRIGGER trg_match_result_immutable
    BEFORE UPDATE ON match_result
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_update();

CREATE TRIGGER trg_review_snapshot_immutable
    BEFORE UPDATE ON review_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_update();

CREATE TRIGGER trg_review_decision_immutable
    BEFORE UPDATE ON review_decision
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_update();
