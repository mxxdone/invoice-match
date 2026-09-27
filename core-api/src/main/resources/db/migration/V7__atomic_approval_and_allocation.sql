-- P1-07 atomic approval: ReceiptAllocation, ReviewDecision(APPROVED) and one
-- internal PaymentRequest per approved ReviewSnapshot, all in one transaction.
--
-- The approval transaction (Spec 13.1) locks the authoritative invoice case,
-- the purchase order advisory lock used by refresh/review, then the referenced
-- receipt lines in a deterministic order, and only then consumes confirmed
-- receipt balance. This migration supplies the local allocation truth (ADR 0002)
-- and the declarative guards that make an over-allocation or a cross-case
-- reference impossible even under raw SQL:
--
-- 1. receipt_allocation is append-only and references the exact case, approved
--    decision, review snapshot/evidence bundle and receipt line. Composite
--    foreign keys prove every referenced row belongs to the same case/purchase
--    order, so a receipt line of another claim cannot be consumed.
-- 2. A BEFORE INSERT trigger locks the target receipt line and rejects any
--    insert whose running allocated sum would exceed the externally confirmed
--    quantity. It serializes concurrent allocators at the database, not in the
--    JVM.
-- 3. payment_request holds exactly one internal pending record per approved
--    snapshot with a deterministic globally unique external request key.
-- 4. The V6 audit action check is extended with APPROVE (V1-V6 are untouched).
--
-- Outbox, ERP delivery and status transitions are P1-08 and are deliberately
-- absent here.

-- ---------------------------------------------------------------------------
-- 0. Composite candidate keys for same-subject declarative foreign keys
-- ---------------------------------------------------------------------------

ALTER TABLE invoice_case
    ADD CONSTRAINT ux_invoice_case_id_purchase_order UNIQUE (id, purchase_order_id);

ALTER TABLE review_decision
    ADD CONSTRAINT ux_review_decision_id_case UNIQUE (id, invoice_case_id);

ALTER TABLE receipt_line_snapshot
    ADD CONSTRAINT ux_receipt_line_snapshot_id_purchase_order UNIQUE (id, purchase_order_id);

-- ---------------------------------------------------------------------------
-- 1. ReceiptAllocation: append-only consumption of a receipt line
-- ---------------------------------------------------------------------------

CREATE TABLE receipt_allocation (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL,
    purchase_order_id varchar(64) NOT NULL,
    review_decision_id uuid NOT NULL,
    review_snapshot_id uuid NOT NULL,
    evidence_bundle_id uuid NOT NULL,
    invoice_line_number integer NOT NULL,
    receipt_line_snapshot_id uuid NOT NULL,
    receipt_id varchar(64) NOT NULL,
    receipt_line_id varchar(64) NOT NULL,
    allocated_quantity integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_receipt_allocation_quantity CHECK (allocated_quantity > 0),
    CONSTRAINT ck_receipt_allocation_line_number CHECK (invoice_line_number > 0),
    CONSTRAINT fk_receipt_allocation_case_purchase_order
        FOREIGN KEY (invoice_case_id, purchase_order_id)
        REFERENCES invoice_case (id, purchase_order_id),
    CONSTRAINT fk_receipt_allocation_decision_same_case
        FOREIGN KEY (review_decision_id, invoice_case_id)
        REFERENCES review_decision (id, invoice_case_id),
    CONSTRAINT fk_receipt_allocation_snapshot_same_case_bundle
        FOREIGN KEY (review_snapshot_id, invoice_case_id, evidence_bundle_id)
        REFERENCES review_snapshot (id, invoice_case_id, evidence_bundle_id),
    CONSTRAINT fk_receipt_allocation_receipt_line_same_purchase_order
        FOREIGN KEY (receipt_line_snapshot_id, purchase_order_id)
        REFERENCES receipt_line_snapshot (id, purchase_order_id)
);

CREATE INDEX ix_receipt_allocation_case
    ON receipt_allocation (invoice_case_id);

CREATE INDEX ix_receipt_allocation_receipt_line
    ON receipt_allocation (receipt_line_snapshot_id);

CREATE INDEX ix_receipt_allocation_snapshot
    ON receipt_allocation (review_snapshot_id);

-- Allocations are an append-only ledger. The V1 reject_immutable_change trigger
-- is reused for both UPDATE and DELETE.
CREATE TRIGGER trg_receipt_allocation_immutable
    BEFORE UPDATE OR DELETE ON receipt_allocation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

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
-- 2. PaymentRequest: exactly one internal record per approved snapshot
-- ---------------------------------------------------------------------------

CREATE TABLE payment_request (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL,
    purchase_order_id varchar(64) NOT NULL,
    review_decision_id uuid NOT NULL,
    review_snapshot_id uuid NOT NULL,
    evidence_bundle_id uuid NOT NULL,
    external_request_key varchar(200) NOT NULL,
    amount bigint NOT NULL,
    currency varchar(3) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_payment_request_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_request_currency CHECK (currency = 'KRW'),
    -- P1-08 expands the export lifecycle; P1-07 only creates the pending record.
    CONSTRAINT ck_payment_request_status CHECK (status IN ('PENDING')),
    CONSTRAINT ux_payment_request_external_key UNIQUE (external_request_key),
    -- One payment request per approval snapshot (Spec 14.2 key
    -- invoiceCaseId + reviewSnapshotId). A second approval of the same snapshot
    -- cannot create a second logical payment.
    CONSTRAINT ux_payment_request_case_snapshot UNIQUE (invoice_case_id, review_snapshot_id),
    CONSTRAINT fk_payment_request_case_purchase_order
        FOREIGN KEY (invoice_case_id, purchase_order_id)
        REFERENCES invoice_case (id, purchase_order_id),
    CONSTRAINT fk_payment_request_decision_same_case
        FOREIGN KEY (review_decision_id, invoice_case_id)
        REFERENCES review_decision (id, invoice_case_id),
    CONSTRAINT fk_payment_request_snapshot_same_case_bundle
        FOREIGN KEY (review_snapshot_id, invoice_case_id, evidence_bundle_id)
        REFERENCES review_snapshot (id, invoice_case_id, evidence_bundle_id)
);

CREATE INDEX ix_payment_request_case
    ON payment_request (invoice_case_id);

-- ---------------------------------------------------------------------------
-- 3. Extend the audit action vocabulary with APPROVE
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
