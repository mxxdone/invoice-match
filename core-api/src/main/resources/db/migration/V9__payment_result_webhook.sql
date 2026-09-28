-- P1-09 mock-ERP result webhook and idempotent convergence.
--
-- P1-08's relay remains the only writer that advances a payment through a
-- *send* attempt: it owns Claimed/Sending/Delivered and the attempt ledger.
-- P1-09 adds a second, narrower writer: an externally observed ERP result
-- (webhook, or an operator-triggered status inquiry) may resolve a payment whose
-- send outcome was unknown. It never starts a new send and never creates a new
-- PaymentRequest or export key.
--
-- The external result is itself durable evidence. V9 adds:
--   1. payment_result_event: an append-only, deduplicated record of every
--      accepted external result, keyed by (provider, external_event_id) with the
--      exported idempotency key bound to the outbox row.
--   2. A deferred commit guard that only lets a result event commit when its
--      claimed outcome exactly matches the committed payment/outbox/case tuple.
--   3. The two convergence transitions the P1-08 matrix deliberately left out:
--      RESULT_UNKNOWN -> ACKNOWLEDGED and RESULT_UNKNOWN -> FAILED for both the
--      payment and its outbox. FAILED and RESULT_UNKNOWN stay distinct; a failed
--      result never reaches EXPORTED and an acknowledged result always does.
--   4. The outbox evidence trigger accepts a payment_result_event as evidence
--      for a webhook-driven DELIVERED/FAILED transition (there is no relay claim
--      token on a RESULT_UNKNOWN row), while the relay path keeps requiring the
--      attempt-ledger evidence exactly as in V8.
--
-- V1-V8 are untouched. The webhook controller, signature verification and
-- idempotent application service are P1-09 Java.

-- ---------------------------------------------------------------------------
-- 1. PaymentRequest: allow an authoritative external result to resolve
--    RESULT_UNKNOWN without ever reopening a send
-- ---------------------------------------------------------------------------

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
        OR NEW.export_version IS DISTINCT FROM OLD.export_version
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'payment_request subject, money, key and version are immutable'
            USING ERRCODE = '23000';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'PENDING' AND NEW.status = 'NOT_SENT')
            OR (OLD.status IN ('PENDING', 'NOT_SENT') AND NEW.status = 'SENDING')
            OR (OLD.status = 'SENDING' AND NEW.status IN (
                'ACKNOWLEDGED', 'RETRY_SCHEDULED', 'FAILED', 'RESULT_UNKNOWN'))
            OR (OLD.status = 'RETRY_SCHEDULED' AND NEW.status = 'SENDING')
            -- P1-09: an authoritative external result resolves an unknown send.
            OR (OLD.status = 'RESULT_UNKNOWN' AND NEW.status IN ('ACKNOWLEDGED', 'FAILED'))) THEN
            RAISE EXCEPTION 'illegal payment_request status transition % -> %',
                OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 2. OutboxEvent: allow RESULT_UNKNOWN -> DELIVERED/FAILED for a result event
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION guard_outbox_event_update() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox_event % is not deletable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.payment_request_id IS DISTINCT FROM OLD.payment_request_id
        OR NEW.invoice_case_id IS DISTINCT FROM OLD.invoice_case_id
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.export_version IS DISTINCT FROM OLD.export_version
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'outbox event subject, payload, hash and key are immutable'
            USING ERRCODE = '23000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'outbox attempt_count must not decrease'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'READY' AND NEW.status = 'CLAIMED')
            OR (OLD.status = 'CLAIMED' AND NEW.status IN ('READY', 'SENDING'))
            OR (OLD.status = 'SENDING' AND NEW.status IN (
                'READY', 'DELIVERED', 'FAILED', 'RESULT_UNKNOWN'))
            -- P1-09: a verified external result resolves an unknown send.
            OR (OLD.status = 'RESULT_UNKNOWN' AND NEW.status IN ('DELIVERED', 'FAILED'))) THEN
            RAISE EXCEPTION 'illegal outbox status transition % -> %', OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    ELSE
        -- Same-state updates cannot steal or rewrite a claim identity.
        IF NEW.worker_id IS DISTINCT FROM OLD.worker_id
            OR NEW.claim_token IS DISTINCT FROM OLD.claim_token
            OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count
            OR NEW.delivered_at IS DISTINCT FROM OLD.delivered_at THEN
            RAISE EXCEPTION 'outbox claim identity cannot change without a status transition'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.status IN ('CLAIMED', 'SENDING') THEN
        IF NEW.worker_id IS NULL OR NEW.claim_token IS NULL OR NEW.lease_expires_at IS NULL THEN
            RAISE EXCEPTION 'outbox % requires worker, claim token and lease deadline', NEW.status
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.worker_id IS NOT NULL OR NEW.claim_token IS NOT NULL
            OR NEW.lease_expires_at IS NOT NULL THEN
            RAISE EXCEPTION 'outbox % must not carry a lease or claim token', NEW.status
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.status = 'DELIVERED' THEN
        IF NEW.delivered_at IS NULL THEN
            RAISE EXCEPTION 'DELIVERED outbox requires delivered_at' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.delivered_at IS NOT NULL THEN
        RAISE EXCEPTION 'only a DELIVERED outbox may carry delivered_at' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 3. payment_result_event: append-only, deduplicated external result evidence
-- ---------------------------------------------------------------------------

-- The external result is keyed by (provider, external_event_id) exactly as Spec
-- 14.2 requires. The exported idempotency key is stored verbatim and a foreign
-- key binds the row to the outbox event that produced it. No credential or raw
-- webhook payload is stored; only a bounded canonical hash and the external
-- reference. Rows are never updated or deleted.
CREATE TABLE payment_result_event (
    id uuid PRIMARY KEY,
    provider varchar(64) NOT NULL,
    external_event_id varchar(200) NOT NULL,
    external_payment_key varchar(200) NOT NULL,
    payment_request_id uuid NOT NULL,
    outbox_event_id uuid NOT NULL,
    outcome varchar(32) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    external_reference varchar(200),
    received_at timestamptz NOT NULL,
    CONSTRAINT ck_payment_result_event_provider CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_payment_result_event_event_id CHECK (btrim(external_event_id) <> ''),
    CONSTRAINT ck_payment_result_event_payment_key CHECK (btrim(external_payment_key) <> ''),
    CONSTRAINT ck_payment_result_event_outcome CHECK (outcome IN ('ACKNOWLEDGED', 'FAILED')),
    CONSTRAINT ck_payment_result_event_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ux_payment_result_event_provider_event UNIQUE (provider, external_event_id),
    CONSTRAINT fk_payment_result_event_payment
        FOREIGN KEY (payment_request_id) REFERENCES payment_request (id),
    CONSTRAINT fk_payment_result_event_outbox
        FOREIGN KEY (outbox_event_id) REFERENCES outbox_event (id)
);

CREATE INDEX ix_payment_result_event_payment ON payment_result_event (payment_request_id);

CREATE TRIGGER trg_payment_result_event_immutable
    BEFORE UPDATE OR DELETE ON payment_result_event
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- A result event can only commit when its claimed outcome is exactly the
-- committed business tuple and its external key is the outbox idempotency key.
-- A standalone forged result row without the matching convergence fails.
CREATE OR REPLACE FUNCTION check_payment_result_event_commit() RETURNS trigger AS $$
DECLARE
    p_status varchar;
    o_status varchar;
    c_status varchar;
    o_key varchar;
BEGIN
    SELECT p.status, o.status, c.status, o.idempotency_key
      INTO p_status, o_status, c_status, o_key
      FROM payment_request p
      JOIN outbox_event o ON o.id = NEW.outbox_event_id AND o.payment_request_id = p.id
      JOIN invoice_case c ON c.id = p.invoice_case_id
     WHERE p.id = NEW.payment_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'payment result event references an unknown payment/outbox pair'
            USING ERRCODE = '23503';
    END IF;
    IF o_key IS DISTINCT FROM NEW.external_payment_key THEN
        RAISE EXCEPTION 'payment result event key % does not match outbox idempotency key %',
            NEW.external_payment_key, o_key USING ERRCODE = '23514';
    END IF;
    IF NEW.outcome = 'ACKNOWLEDGED' THEN
        IF p_status <> 'ACKNOWLEDGED' OR o_status <> 'DELIVERED' OR c_status <> 'EXPORTED' THEN
            RAISE EXCEPTION 'ACKNOWLEDGED result requires the acknowledged payment/outbox/case tuple (%, %, %)',
                p_status, o_status, c_status USING ERRCODE = '23514';
        END IF;
    ELSE
        IF p_status <> 'FAILED' OR o_status <> 'FAILED' OR c_status <> 'EXPORT_PENDING' THEN
            RAISE EXCEPTION 'FAILED result requires the failed payment/outbox/export-pending tuple (%, %, %)',
                p_status, o_status, c_status USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_payment_result_event_commit
    AFTER INSERT ON payment_result_event
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_payment_result_event_commit();

-- ---------------------------------------------------------------------------
-- 4. Outbox evidence: a webhook transition is proven by its result event
-- ---------------------------------------------------------------------------

-- The relay path (SENDING -> DELIVERED/FAILED, CLAIMED -> SENDING, ...) still
-- requires the attempt-ledger evidence exactly as V8. The P1-09 convergence path
-- (RESULT_UNKNOWN -> DELIVERED/FAILED, and a result that races an in-flight send
-- from SENDING) is proven by the committed payment_result_event instead, because
-- a RESULT_UNKNOWN row no longer carries a relay claim token.
CREATE OR REPLACE FUNCTION check_outbox_attempt_evidence() RETURNS trigger AS $$
DECLARE
    proof integer;
    expected_outcome varchar;
    evidence_attempt integer;
    evidence_token uuid;
BEGIN
    IF TG_OP = 'INSERT' OR NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF NEW.status = 'READY' AND OLD.status = 'CLAIMED' THEN
        RETURN NEW;  -- safe lease return, HTTP never started
    END IF;
    IF NEW.status = 'SENDING' AND OLD.status = 'CLAIMED' THEN
        expected_outcome := 'SENDING';
        evidence_attempt := NEW.attempt_count;
        evidence_token := NEW.claim_token;
    ELSIF NEW.status = 'READY' AND OLD.status = 'SENDING' THEN
        expected_outcome := 'RETRY_SCHEDULED';
        evidence_attempt := OLD.attempt_count;
        evidence_token := OLD.claim_token;
    ELSIF NEW.status = 'DELIVERED' THEN
        SELECT count(*) INTO proof FROM payment_result_event r
         WHERE r.outbox_event_id = NEW.id AND r.outcome = 'ACKNOWLEDGED';
        IF proof > 0 THEN
            RETURN NEW;
        END IF;
        expected_outcome := 'ACKNOWLEDGED';
        evidence_attempt := OLD.attempt_count;
        evidence_token := OLD.claim_token;
    ELSIF NEW.status = 'FAILED' THEN
        SELECT count(*) INTO proof FROM payment_result_event r
         WHERE r.outbox_event_id = NEW.id AND r.outcome = 'FAILED';
        IF proof > 0 THEN
            RETURN NEW;
        END IF;
        expected_outcome := 'FAILED';
        evidence_attempt := OLD.attempt_count;
        evidence_token := OLD.claim_token;
    ELSIF NEW.status = 'RESULT_UNKNOWN' THEN
        SELECT count(*) INTO proof FROM outbox_delivery_attempt
         WHERE outbox_event_id = NEW.id
           AND payment_request_id = NEW.payment_request_id
           AND attempt_number = OLD.attempt_count
           AND claim_token = OLD.claim_token
           AND outcome IN ('RESULT_UNKNOWN', 'LEASE_EXPIRED');
        IF proof = 0 THEN
            RAISE EXCEPTION 'RESULT_UNKNOWN outbox % has no matching delivery attempt', NEW.id
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    ELSE
        RETURN NEW;
    END IF;
    SELECT count(*) INTO proof FROM outbox_delivery_attempt
     WHERE outbox_event_id = NEW.id
       AND payment_request_id = NEW.payment_request_id
       AND attempt_number = evidence_attempt
       AND claim_token = evidence_token
       AND outcome = expected_outcome;
    IF proof = 0 THEN
        RAISE EXCEPTION 'outbox % transition % -> % has no matching % attempt',
            NEW.id, OLD.status, NEW.status, expected_outcome
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
