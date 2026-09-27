-- P1-07 atomic approval: ReceiptAllocation, ReviewDecision(APPROVED) and one
-- internal PaymentRequest per approved ReviewSnapshot, all in one transaction.
--
-- The approval transaction (Spec 13.1) locks the authoritative invoice case,
-- the purchase order advisory lock used by refresh/review, then the referenced
-- receipt lines in a deterministic order, and only then consumes confirmed
-- receipt balance. This migration supplies the local allocation truth (ADR 0002)
-- and the declarative/trigger guards that make every approval relationship
-- unforgeable even under raw SQL:
--
-- 1. review_decision gains the approved amount/currency for APPROVED decisions
--    plus composite candidate keys so receipt_allocation and payment_request can
--    bind the exact decision subject (case + snapshot + payload hash) and money.
-- 2. receipt_allocation is append-only and, at INSERT, a trigger proves the
--    decision is APPROVED and belongs to the exact case/snapshot/hash, the
--    evidence bundle belongs to the case, the invoice line really belongs to the
--    bundle's sealed draft revision, and the receipt line internal UUID matches
--    the same purchase order, stored external ids, version and active state. A
--    unique key forbids a duplicate allocation of one line/receipt.
-- 3. A BEFORE INSERT trigger locks the target receipt line and rejects any
--    insert whose running allocated sum would exceed the confirmed quantity.
-- 4. payment_request binds the exact APPROVED decision money/subject with a
--    composite foreign key, enforces the deterministic external key and a
--    positive amount, and protects subject/money/key from UPDATE and DELETE so
--    P1-08 can only advance the delivery status.
-- 5. The V6 audit validation is replaced (V6 is untouched) so APPROVE must
--    target the exact APPROVED decision of the case by its own actor, name the
--    exact snapshot/hash and payment request, and carry matching allocation
--    totals and REVIEW_PENDING -> EXPORT_PENDING states.
--
-- Outbox, ERP delivery and status transitions are P1-08 and are deliberately
-- absent here.

-- ---------------------------------------------------------------------------
-- 0. Composite candidate keys for same-subject declarative foreign keys
-- ---------------------------------------------------------------------------

ALTER TABLE invoice_case
    ADD CONSTRAINT ux_invoice_case_id_purchase_order UNIQUE (id, purchase_order_id);

ALTER TABLE receipt_line_snapshot
    ADD CONSTRAINT ux_receipt_line_snapshot_id_purchase_order UNIQUE (id, purchase_order_id);

-- ---------------------------------------------------------------------------
-- 1. review_decision: approved money and subject candidate keys
-- ---------------------------------------------------------------------------

ALTER TABLE review_decision
    ADD COLUMN approved_amount bigint,
    ADD COLUMN approved_currency varchar(3);

ALTER TABLE review_decision
    ADD CONSTRAINT ck_review_decision_approved_money CHECK (
        (decision = 'APPROVED' AND approved_amount IS NOT NULL AND approved_currency = 'KRW')
        OR (decision <> 'APPROVED' AND approved_amount IS NULL AND approved_currency IS NULL)) NOT VALID,
    ADD CONSTRAINT ck_review_decision_approved_amount CHECK (approved_amount IS NULL OR approved_amount > 0)
        NOT VALID,
    -- Exact decision subject: id + case + snapshot + payload hash. Referenced by
    -- receipt_allocation so an allocation can never point at a decision of
    -- another case/snapshot/hash.
    ADD CONSTRAINT ux_review_decision_subject UNIQUE (
        id, invoice_case_id, review_snapshot_id, payload_hash),
    -- Exact approved money subject, referenced by payment_request so a payment
    -- can never assert an amount/currency the approved decision did not carry.
    ADD CONSTRAINT ux_review_decision_approval UNIQUE (
        id, invoice_case_id, review_snapshot_id, payload_hash, approved_amount, approved_currency);

-- ---------------------------------------------------------------------------
-- 2. ReceiptAllocation: append-only, unforgeable consumption of a receipt line
-- ---------------------------------------------------------------------------

CREATE TABLE receipt_allocation (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL,
    purchase_order_id varchar(64) NOT NULL,
    review_decision_id uuid NOT NULL,
    review_snapshot_id uuid NOT NULL,
    review_payload_hash varchar(128) NOT NULL,
    evidence_bundle_id uuid NOT NULL,
    invoice_line_number integer NOT NULL,
    receipt_line_snapshot_id uuid NOT NULL,
    receipt_id varchar(64) NOT NULL,
    receipt_line_id varchar(64) NOT NULL,
    purchase_order_line_id varchar(64) NOT NULL,
    receipt_line_version bigint NOT NULL,
    allocated_quantity integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_receipt_allocation_quantity CHECK (allocated_quantity > 0),
    CONSTRAINT ck_receipt_allocation_line_number CHECK (invoice_line_number > 0),
    CONSTRAINT ck_receipt_allocation_line_version CHECK (receipt_line_version >= 0),
    CONSTRAINT fk_receipt_allocation_case_purchase_order
        FOREIGN KEY (invoice_case_id, purchase_order_id)
        REFERENCES invoice_case (id, purchase_order_id),
    CONSTRAINT fk_receipt_allocation_decision_subject
        FOREIGN KEY (review_decision_id, invoice_case_id, review_snapshot_id, review_payload_hash)
        REFERENCES review_decision (id, invoice_case_id, review_snapshot_id, payload_hash),
    CONSTRAINT fk_receipt_allocation_snapshot_same_case_bundle
        FOREIGN KEY (review_snapshot_id, invoice_case_id, evidence_bundle_id)
        REFERENCES review_snapshot (id, invoice_case_id, evidence_bundle_id),
    CONSTRAINT fk_receipt_allocation_receipt_line_same_purchase_order
        FOREIGN KEY (receipt_line_snapshot_id, purchase_order_id)
        REFERENCES receipt_line_snapshot (id, purchase_order_id),
    -- One allocation per approved decision + invoice line + receipt line: no
    -- duplicate row may consume the same balance twice.
    CONSTRAINT ux_receipt_allocation_decision_line_receipt UNIQUE (
        review_decision_id, invoice_line_number, receipt_line_snapshot_id)
);

CREATE INDEX ix_receipt_allocation_case ON receipt_allocation (invoice_case_id);
CREATE INDEX ix_receipt_allocation_receipt_line ON receipt_allocation (receipt_line_snapshot_id);
CREATE INDEX ix_receipt_allocation_snapshot ON receipt_allocation (review_snapshot_id);

-- Allocations are an append-only ledger. The V1 reject_immutable_change trigger
-- is reused for both UPDATE and DELETE.
CREATE TRIGGER trg_receipt_allocation_immutable
    BEFORE UPDATE OR DELETE ON receipt_allocation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- The allocation must reference an APPROVED decision of the exact case/snapshot/
-- hash, an evidence bundle of the case whose sealed draft actually holds the
-- invoice line, and a receipt line of the same purchase order with the stored
-- external ids, version and active state. Composite FKs already prove most of
-- this; this trigger adds the decision type and the draft-line/active/version
-- facts a static FK cannot express without breaking deactivation history.
CREATE OR REPLACE FUNCTION validate_receipt_allocation() RETURNS trigger AS $$
DECLARE
    decision_row record;
    snapshot_row record;
    invoice_line_found integer;
    receipt_row record;
BEGIN
    SELECT id, decision, invoice_case_id, review_snapshot_id, payload_hash
      INTO decision_row
      FROM review_decision
     WHERE id = NEW.review_decision_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'receipt allocation references a missing review decision %', NEW.review_decision_id
            USING ERRCODE = '23514';
    END IF;
    IF decision_row.decision <> 'APPROVED' THEN
        RAISE EXCEPTION 'receipt allocation decision % is not APPROVED', NEW.review_decision_id
            USING ERRCODE = '23514';
    END IF;
    IF decision_row.invoice_case_id <> NEW.invoice_case_id
        OR decision_row.review_snapshot_id <> NEW.review_snapshot_id
        OR decision_row.payload_hash <> NEW.review_payload_hash THEN
        RAISE EXCEPTION 'receipt allocation decision subject does not match case/snapshot/hash'
            USING ERRCODE = '23514';
    END IF;

    SELECT invoice_case_id, evidence_bundle_id, payload_hash
      INTO snapshot_row
      FROM review_snapshot
     WHERE id = NEW.review_snapshot_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'receipt allocation references a missing review snapshot %', NEW.review_snapshot_id
            USING ERRCODE = '23514';
    END IF;
    IF snapshot_row.invoice_case_id <> NEW.invoice_case_id
        OR snapshot_row.evidence_bundle_id <> NEW.evidence_bundle_id
        OR snapshot_row.payload_hash <> NEW.review_payload_hash THEN
        RAISE EXCEPTION 'receipt allocation snapshot subject does not match case/bundle/hash'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO invoice_line_found
      FROM evidence_bundle b
      JOIN invoice_line l ON l.draft_revision_id = b.draft_revision_id
     WHERE b.id = NEW.evidence_bundle_id
       AND b.invoice_case_id = NEW.invoice_case_id
       AND l.invoice_case_id = NEW.invoice_case_id
       AND l.line_number = NEW.invoice_line_number;
    IF invoice_line_found = 0 THEN
        RAISE EXCEPTION 'receipt allocation invoice line % is not part of the evidence bundle % sealed draft',
            NEW.invoice_line_number, NEW.evidence_bundle_id
            USING ERRCODE = '23514';
    END IF;

    SELECT purchase_order_id, receipt_id, receipt_line_id, purchase_order_line_id,
           receipt_line_version, active
      INTO receipt_row
      FROM receipt_line_snapshot
     WHERE id = NEW.receipt_line_snapshot_id
       FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'receipt allocation references a missing receipt line %', NEW.receipt_line_snapshot_id
            USING ERRCODE = '23514';
    END IF;
    IF NOT receipt_row.active THEN
        RAISE EXCEPTION 'receipt allocation references an inactive receipt line %', NEW.receipt_line_id
            USING ERRCODE = '23514';
    END IF;
    IF receipt_row.purchase_order_id <> NEW.purchase_order_id
        OR receipt_row.receipt_id <> NEW.receipt_id
        OR receipt_row.receipt_line_id <> NEW.receipt_line_id
        OR receipt_row.purchase_order_line_id <> NEW.purchase_order_line_id
        OR receipt_row.receipt_line_version <> NEW.receipt_line_version THEN
        RAISE EXCEPTION 'receipt allocation receipt facts do not match the stored receipt line %',
            NEW.receipt_line_snapshot_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_allocation_validate
    BEFORE INSERT ON receipt_allocation
    FOR EACH ROW EXECUTE FUNCTION validate_receipt_allocation();

-- Over-allocation guard. It locks the exact receipt line row before reading the
-- committed allocation sum, so two concurrent inserts for the same line
-- serialize: the loser observes the winner's committed allocation and is
-- rejected. This holds for raw SQL too, so application and database agree.
CREATE OR REPLACE FUNCTION guard_receipt_allocation_balance() RETURNS trigger AS $$
DECLARE
    confirmed integer;
    allocated bigint;
BEGIN
    SELECT confirmed_quantity INTO confirmed
      FROM receipt_line_snapshot
     WHERE id = NEW.receipt_line_snapshot_id
       FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'receipt line % does not exist', NEW.receipt_line_snapshot_id
            USING ERRCODE = '23503';
    END IF;
    SELECT COALESCE(SUM(allocated_quantity), 0) INTO allocated
      FROM receipt_allocation
     WHERE receipt_line_snapshot_id = NEW.receipt_line_snapshot_id;
    IF allocated + NEW.allocated_quantity > confirmed THEN
        RAISE EXCEPTION 'receipt allocation % for line % exceeds confirmed quantity % (% already allocated)',
            NEW.allocated_quantity, NEW.receipt_line_snapshot_id, confirmed, allocated
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_allocation_balance
    BEFORE INSERT ON receipt_allocation
    FOR EACH ROW EXECUTE FUNCTION guard_receipt_allocation_balance();

-- ---------------------------------------------------------------------------
-- 3. PaymentRequest: exactly one protected internal record per approved snapshot
-- ---------------------------------------------------------------------------

CREATE TABLE payment_request (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL,
    purchase_order_id varchar(64) NOT NULL,
    review_decision_id uuid NOT NULL,
    review_snapshot_id uuid NOT NULL,
    review_payload_hash varchar(128) NOT NULL,
    evidence_bundle_id uuid NOT NULL,
    external_request_key varchar(200) NOT NULL,
    amount bigint NOT NULL,
    currency varchar(3) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_payment_request_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_request_currency CHECK (currency = 'KRW'),
    -- P1-08 expands the export lifecycle; P1-07 only creates the pending record.
    CONSTRAINT ck_payment_request_status CHECK (status IN ('PENDING')),
    -- The external key is deterministic from the exact case and snapshot.
    CONSTRAINT ck_payment_request_external_key CHECK (
        external_request_key = 'PAYMENT:' || invoice_case_id::text || ':' || review_snapshot_id::text),
    CONSTRAINT ux_payment_request_external_key UNIQUE (external_request_key),
    -- One payment request per approval snapshot (Spec 14.2 key
    -- invoiceCaseId + reviewSnapshotId).
    CONSTRAINT ux_payment_request_case_snapshot UNIQUE (invoice_case_id, review_snapshot_id),
    CONSTRAINT fk_payment_request_case_purchase_order
        FOREIGN KEY (invoice_case_id, purchase_order_id)
        REFERENCES invoice_case (id, purchase_order_id),
    CONSTRAINT fk_payment_request_snapshot_same_case_bundle
        FOREIGN KEY (review_snapshot_id, invoice_case_id, evidence_bundle_id)
        REFERENCES review_snapshot (id, invoice_case_id, evidence_bundle_id),
    -- Binds the exact APPROVED decision subject and approved amount/currency.
    CONSTRAINT fk_payment_request_decision_approval
        FOREIGN KEY (
            review_decision_id, invoice_case_id, review_snapshot_id, review_payload_hash, amount, currency)
        REFERENCES review_decision (
            id, invoice_case_id, review_snapshot_id, payload_hash, approved_amount, approved_currency)
);

CREATE INDEX ix_payment_request_case ON payment_request (invoice_case_id);

-- Subject, money and key are immutable. Only the delivery lifecycle fields
-- (currently status, expanded by P1-08) may change; DELETE is always rejected.
CREATE OR REPLACE FUNCTION guard_payment_request_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment_request % is not deletable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.invoice_case_id IS DISTINCT FROM OLD.invoice_case_id
        OR NEW.purchase_order_id IS DISTINCT FROM OLD.purchase_order_id
        OR NEW.review_decision_id IS DISTINCT FROM OLD.review_decision_id
        OR NEW.review_snapshot_id IS DISTINCT FROM OLD.review_snapshot_id
        OR NEW.review_payload_hash IS DISTINCT FROM OLD.review_payload_hash
        OR NEW.evidence_bundle_id IS DISTINCT FROM OLD.evidence_bundle_id
        OR NEW.external_request_key IS DISTINCT FROM OLD.external_request_key
        OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'payment_request subject, money and key are immutable'
            USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_request_protect
    BEFORE UPDATE OR DELETE ON payment_request
    FOR EACH ROW EXECUTE FUNCTION guard_payment_request_mutation();

-- ---------------------------------------------------------------------------
-- 4. Extend the audit action vocabulary with APPROVE
-- ---------------------------------------------------------------------------

ALTER TABLE audit_entry DROP CONSTRAINT ck_audit_entry_action;
ALTER TABLE audit_entry ADD CONSTRAINT ck_audit_entry_action CHECK (action IN (
    'CASE_CREATED',
    'DRAFT_LINES_REPLACED',
    'CASE_SUBMITTED',
    'SUPPLEMENT_REVISION_OPENED',
    'MATCH_RUN',
    'REVIEW_SNAPSHOT_FROZEN',
    'ITEM_MAPPED',
    'SUPPLEMENT_REQUESTED',
    'CASE_REJECTED',
    'APPROVE'));

-- ---------------------------------------------------------------------------
-- 5. Replace the audit validation (V6 file untouched) with APPROVE proof
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION validate_audit_entry() RETURNS trigger AS $$
DECLARE
    parts text[];
    canon text;
    distinct_count integer;
    current_version bigint;
    target_found integer;
    decision_row record;
    payment_found integer;
    allocation_count integer;
    allocation_total bigint;
    payment_amount bigint;
    payment_currency text;
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

    -- Business version must match the case version at insertion (share lock
    -- prevents a concurrent privileged raw-SQL version change).
    SELECT version INTO current_version FROM invoice_case WHERE id = NEW.invoice_case_id FOR SHARE;
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

    -- APPROVE must prove the approval from relational records, not from the
    -- stated summary alone.
    IF NEW.action = 'APPROVE' THEN
        IF NEW.target_type <> 'REVIEW_DECISION' THEN
            RAISE EXCEPTION 'APPROVE audit target must be a REVIEW_DECISION'
                USING ERRCODE = '23514';
        END IF;
        SELECT id, decision, invoice_case_id, review_snapshot_id, payload_hash, decided_by
          INTO decision_row
          FROM review_decision
         WHERE id = NEW.target_id::uuid;
        IF decision_row.decision <> 'APPROVED' THEN
            RAISE EXCEPTION 'APPROVE audit target % is not an APPROVED decision', NEW.target_id
                USING ERRCODE = '23514';
        END IF;
        IF decision_row.decided_by <> NEW.actor THEN
            RAISE EXCEPTION 'APPROVE audit actor % is not the decision approver %',
                NEW.actor, decision_row.decided_by USING ERRCODE = '23514';
        END IF;
        IF NOT ('APPROVER' = ANY(parts)) THEN
            RAISE EXCEPTION 'APPROVE audit actor % does not hold APPROVER', NEW.actor
                USING ERRCODE = '23514';
        END IF;
        IF NEW.request_id IS NULL OR btrim(NEW.request_id) = '' THEN
            RAISE EXCEPTION 'APPROVE audit requires a request id' USING ERRCODE = '23514';
        END IF;
        IF NEW.before_state IS NULL OR NEW.after_state IS NULL THEN
            RAISE EXCEPTION 'APPROVE audit requires before and after state' USING ERRCODE = '23514';
        END IF;
        IF NEW.before_state->>'status' <> 'REVIEW_PENDING'
            OR NEW.after_state->>'status' <> 'EXPORT_PENDING' THEN
            RAISE EXCEPTION 'APPROVE audit must record REVIEW_PENDING -> EXPORT_PENDING'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.after_state->>'reviewSnapshotId' IS NULL
            OR NEW.after_state->>'reviewSnapshotId' <> decision_row.review_snapshot_id::text
            OR NEW.after_state->>'reviewPayloadHash' IS NULL
            OR NEW.after_state->>'reviewPayloadHash' <> decision_row.payload_hash THEN
            RAISE EXCEPTION 'APPROVE audit snapshot fact does not match the approved decision'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*) INTO payment_found
          FROM payment_request
         WHERE id = (NEW.after_state->>'paymentRequestId')::uuid
           AND review_decision_id = decision_row.id
           AND invoice_case_id = NEW.invoice_case_id
           AND review_snapshot_id = decision_row.review_snapshot_id
           AND review_payload_hash = decision_row.payload_hash;
        IF payment_found <> 1 THEN
            RAISE EXCEPTION 'APPROVE audit payment request does not match the approved decision'
                USING ERRCODE = '23514';
        END IF;
        SELECT amount, currency INTO payment_amount, payment_currency
          FROM payment_request WHERE id = (NEW.after_state->>'paymentRequestId')::uuid;
        IF NEW.after_state->>'amount' IS NULL
            OR (NEW.after_state->>'amount')::bigint <> payment_amount
            OR NEW.after_state->>'currency' <> payment_currency THEN
            RAISE EXCEPTION 'APPROVE audit money fact does not match the payment request'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*), COALESCE(SUM(allocated_quantity), 0)
          INTO allocation_count, allocation_total
          FROM receipt_allocation
         WHERE review_decision_id = decision_row.id
           AND invoice_case_id = NEW.invoice_case_id;
        IF NEW.after_state->>'allocationCount' IS NULL
            OR (NEW.after_state->>'allocationCount')::bigint <> allocation_count
            OR NEW.after_state->>'allocatedQuantity' IS NULL
            OR (NEW.after_state->>'allocatedQuantity')::bigint <> allocation_total THEN
            RAISE EXCEPTION 'APPROVE audit allocation facts do not match the committed allocations'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
