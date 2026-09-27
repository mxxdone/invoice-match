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
    ADD COLUMN approved_currency varchar(3),
    ADD COLUMN approved_case_version_before bigint,
    ADD COLUMN approved_case_version_after bigint;

ALTER TABLE review_decision
    ADD CONSTRAINT ck_review_decision_approved_money CHECK (
        (decision = 'APPROVED'
            AND approved_amount IS NOT NULL
            AND approved_currency = 'KRW'
            AND approved_case_version_before IS NOT NULL
            AND approved_case_version_after = approved_case_version_before + 1)
        OR (decision <> 'APPROVED'
            AND approved_amount IS NULL
            AND approved_currency IS NULL
            AND approved_case_version_before IS NULL
            AND approved_case_version_after IS NULL)) NOT VALID,
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
    confirmed_quantity_at_approval integer NOT NULL,
    allocated_quantity integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_receipt_allocation_quantity CHECK (allocated_quantity > 0),
    CONSTRAINT ck_receipt_allocation_line_number CHECK (invoice_line_number > 0),
    CONSTRAINT ck_receipt_allocation_line_version CHECK (receipt_line_version >= 0),
    CONSTRAINT ck_receipt_allocation_confirmed_quantity CHECK (confirmed_quantity_at_approval >= 0),
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

-- Single BEFORE INSERT guard. It acquires locks in the SAME canonical order the
-- application uses (invoice case -> purchase order advisory -> receipt line),
-- then validates the exact decision/snapshot/bundle/draft-line/receipt facts and
-- finally the checked balance. One ordered trigger replaces separate
-- alphabetically-ordered validate/balance triggers so a raw INSERT can never
-- acquire receipt locks in a reverse order relative to an approval.
CREATE OR REPLACE FUNCTION guard_receipt_allocation_insert() RETURNS trigger AS $$
DECLARE
    decision_row record;
    snapshot_row record;
    invoice_line_found integer;
    receipt_row record;
    allocated bigint;
BEGIN
    -- 1. authoritative case first.
    PERFORM 1 FROM invoice_case WHERE id = NEW.invoice_case_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'receipt allocation references a missing case %', NEW.invoice_case_id
            USING ERRCODE = '23514';
    END IF;

    -- 2. the same purchase order advisory lock the refresh/approval protocol uses.
    PERFORM pg_advisory_xact_lock(1, hashtext(NEW.purchase_order_id));

    -- 3. validate the exact decision subject.
    SELECT id, decision, decision_number, invoice_case_id, review_snapshot_id, payload_hash
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

    -- 4. lock the exact receipt line.
    SELECT purchase_order_id, receipt_id, receipt_line_id, purchase_order_line_id,
           receipt_line_version, confirmed_quantity, active
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
        OR receipt_row.receipt_line_version <> NEW.receipt_line_version
        OR receipt_row.confirmed_quantity <> NEW.confirmed_quantity_at_approval THEN
        RAISE EXCEPTION 'receipt allocation receipt facts do not match the stored receipt line %',
            NEW.receipt_line_snapshot_id
            USING ERRCODE = '23514';
    END IF;

    -- 5. checked balance (the row lock is already held).
    SELECT COALESCE(SUM(allocated_quantity), 0) INTO allocated
      FROM receipt_allocation
     WHERE receipt_line_snapshot_id = NEW.receipt_line_snapshot_id;
    IF allocated + NEW.allocated_quantity > receipt_row.confirmed_quantity THEN
        RAISE EXCEPTION 'receipt allocation % for line % exceeds confirmed quantity % (% already allocated)',
            NEW.allocated_quantity, NEW.receipt_line_snapshot_id, receipt_row.confirmed_quantity, allocated
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_allocation_insert
    BEFORE INSERT ON receipt_allocation
    FOR EACH ROW EXECUTE FUNCTION guard_receipt_allocation_insert();

-- A later purchasing refresh may increase a confirmed quantity or evolve a
-- receipt line, but must never invalidate an already committed allocation: the
-- confirmed quantity cannot drop below the committed allocation sum, and an
-- active line with allocations cannot be deactivated. Increases, version
-- evolution and reactivation stay allowed, and history (the allocation rows and
-- their confirmed_quantity_at_approval) remains intact.
CREATE OR REPLACE FUNCTION guard_receipt_line_allocation_on_update() RETURNS trigger AS $$
DECLARE
    allocated bigint;
BEGIN
    SELECT COALESCE(SUM(allocated_quantity), 0) INTO allocated
      FROM receipt_allocation
     WHERE receipt_line_snapshot_id = NEW.id;
    IF NEW.confirmed_quantity < allocated THEN
        RAISE EXCEPTION 'confirmed quantity % for receipt line % is below committed allocation %',
            NEW.confirmed_quantity, NEW.id, allocated
            USING ERRCODE = '23514';
    END IF;
    IF OLD.active AND NOT NEW.active AND allocated > 0 THEN
        RAISE EXCEPTION 'receipt line % with committed allocations cannot be deactivated', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_line_allocation_guard
    BEFORE UPDATE ON receipt_line_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_receipt_line_allocation_on_update();

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
    payment_row record;
    allocation_count bigint;
    allocation_total bigint;
    allocations_expected jsonb;
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
    -- stated summary alone. Every field the application emits is verified, so a
    -- forged numeric/string/allocation field is rejected even when totals match.
    IF NEW.action = 'APPROVE' THEN
        IF NEW.target_type <> 'REVIEW_DECISION' THEN
            RAISE EXCEPTION 'APPROVE audit target must be a REVIEW_DECISION'
                USING ERRCODE = '23514';
        END IF;
        SELECT id, decision, decision_number, invoice_case_id, review_snapshot_id, payload_hash,
               decided_by, approved_case_version_before, approved_case_version_after
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

        -- States and before/after case versions must match the persisted
        -- authoritative approval metadata on the decision.
        IF NEW.before_state->>'status' IS DISTINCT FROM 'REVIEW_PENDING'
            OR NEW.after_state->>'status' IS DISTINCT FROM 'EXPORT_PENDING' THEN
            RAISE EXCEPTION 'APPROVE audit must record REVIEW_PENDING -> EXPORT_PENDING'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.before_state->>'caseVersion' IS DISTINCT FROM decision_row.approved_case_version_before::text
            OR NEW.after_state->>'caseVersion' IS DISTINCT FROM decision_row.approved_case_version_after::text THEN
            RAISE EXCEPTION 'APPROVE audit case versions do not match the approved decision'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.after_state->>'decisionId' IS DISTINCT FROM decision_row.id::text
            OR NEW.after_state->>'decisionNumber' IS DISTINCT FROM decision_row.decision_number::text
            OR NEW.after_state->>'reviewSnapshotId' IS DISTINCT FROM decision_row.review_snapshot_id::text
            OR NEW.after_state->>'reviewPayloadHash' IS DISTINCT FROM decision_row.payload_hash THEN
            RAISE EXCEPTION 'APPROVE audit decision/snapshot facts do not match the approved decision'
                USING ERRCODE = '23514';
        END IF;

        SELECT id, amount, currency, external_request_key
          INTO payment_row
          FROM payment_request
         WHERE id = (NEW.after_state->>'paymentRequestId')::uuid
           AND review_decision_id = decision_row.id
           AND invoice_case_id = NEW.invoice_case_id
           AND review_snapshot_id = decision_row.review_snapshot_id
           AND review_payload_hash = decision_row.payload_hash;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'APPROVE audit payment request does not match the approved decision'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.after_state->>'externalRequestKey' IS DISTINCT FROM payment_row.external_request_key
            OR NEW.after_state->>'amount' IS DISTINCT FROM payment_row.amount::text
            OR NEW.after_state->>'currency' IS DISTINCT FROM payment_row.currency THEN
            RAISE EXCEPTION 'APPROVE audit money/key fact does not match the payment request'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*), COALESCE(SUM(allocated_quantity), 0)
          INTO allocation_count, allocation_total
          FROM receipt_allocation
         WHERE review_decision_id = NEW.target_id::uuid
           AND invoice_case_id = NEW.invoice_case_id;

        SELECT COALESCE(jsonb_agg(jsonb_build_object(
                   'invoiceLineNumber', a.invoice_line_number,
                   'receiptId', a.receipt_id,
                   'receiptLineId', a.receipt_line_id,
                   'quantity', a.allocated_quantity)
                   ORDER BY a.invoice_line_number, a.receipt_line_id, a.receipt_id), '[]'::jsonb)
          INTO allocations_expected
          FROM receipt_allocation a
         WHERE a.review_decision_id = NEW.target_id::uuid
           AND a.invoice_case_id = NEW.invoice_case_id;

        IF NEW.after_state->>'allocationCount' IS DISTINCT FROM allocation_count::text
            OR NEW.after_state->>'allocatedQuantity' IS DISTINCT FROM allocation_total::text
            OR NEW.after_state->'allocations' IS DISTINCT FROM allocations_expected THEN
            RAISE EXCEPTION 'APPROVE audit allocation facts do not match the committed allocations'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
