-- P1-08 minimal transactional Outbox and in-process HTTP relay.
--
-- The approval transaction already writes exactly one PaymentRequest per
-- approved snapshot. V8 adds the transactional Outbox that guarantees the ERP
-- hand-off is not lost when the relay is stopped or a worker crashes, without
-- introducing RabbitMQ, DLQ, operator replay, webhooks or a generic event
-- framework (Spec 14.1, ADR 0003).
--
-- It provides:
--   1. The expanded PaymentRequest export lifecycle. Legacy PENDING rows are
--      migrated to NOT_SENT (Spec 10.3); the state machine is DB-constrained.
--   2. A canonical, bounded, credential-free export payload that is fully
--      derived from the immutable PaymentRequest. A DB function reproduces the
--      exact text the Java writer uses, using PostgreSQL's typed JSON string
--      escaping so quotes, backslashes, control characters and Unicode are all
--      handled. Triggers bind every Outbox row to that payload/hash/key so raw
--      SQL cannot substitute another payment, amount, currency, key or subject.
--   3. An append-only OutboxEvent whose claim/lease state machine is
--      DB-constrained and whose claim identity (worker/token/attempt) is
--      immutable without a status transition.
--   4. An append-only delivery-attempt ledger whose INSERT guard makes every
--      attempt real transition evidence: a SENDING attempt is only legal while
--      the event is CLAIMED with the same token/worker and attempt_count + 1,
--      and every terminal/retry attempt is only legal while the event is
--      SENDING and the same attempt already has SENDING evidence. The HTTP
--      status must match the outcome, and a deferred constraint trigger binds
--      the outcome to the committed outbox/payment/case state.
--   5. Deferred cross-state guards that validate the entire
--      (payment, outbox, case) tuple in both directions for every payment of a
--      case, so partially-forged combinations cannot commit.
--   6. A deterministic backfill of one READY event (version 1) for every
--      pre-existing PaymentRequest, so a V7 database with committed approvals
--      is not left with an un-exported payment.
--
-- V1-V7 are untouched. The relay and HTTP adapter are P1-08 Java.

-- ---------------------------------------------------------------------------
-- 1. PaymentRequest: expand the export lifecycle and add export version
-- ---------------------------------------------------------------------------

ALTER TABLE payment_request
    ADD COLUMN export_version bigint NOT NULL DEFAULT 1;

ALTER TABLE payment_request
    ADD CONSTRAINT ck_payment_request_export_version CHECK (export_version = 1);

-- P1-07 only emitted PENDING. P1-08 owns the hand-off lifecycle (Spec 10.3).
-- The old constraint is dropped first so the legacy rows can be migrated, and
-- the expanded constraint is added after the UPDATE below.
ALTER TABLE payment_request DROP CONSTRAINT ck_payment_request_status;

-- The subject/money/key guard is replaced (V7 is untouched) so export_version
-- is immutable too and the delivery status can only move along the confirmed
-- export state machine. Legacy PENDING -> NOT_SENT is the one migration edge.
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
            OR (OLD.status = 'RETRY_SCHEDULED' AND NEW.status = 'SENDING')) THEN
            RAISE EXCEPTION 'illegal payment_request status transition % -> %',
                OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

UPDATE payment_request SET status = 'NOT_SENT' WHERE status = 'PENDING';

ALTER TABLE payment_request
    ADD CONSTRAINT ck_payment_request_status CHECK (status IN (
        'NOT_SENT',
        'SENDING',
        'ACKNOWLEDGED',
        'RETRY_SCHEDULED',
        'FAILED',
        'RESULT_UNKNOWN'));

-- ---------------------------------------------------------------------------
-- 2. Canonical payment export payload (single source of truth for SQL + Java)
-- ---------------------------------------------------------------------------

-- Fixed key order (alphabetical), no whitespace, numbers unquoted. Every string
-- value is rendered with PostgreSQL's client-encoding JSON escaping
-- (to_jsonb(text)::text) so quotes, backslashes, TAB/CR/LF and other control
-- characters are escaped and Unicode is emitted as UTF-8, exactly matching the
-- Java PaymentExportPayload. The hash is the lowercase hex SHA-256 of this
-- UTF-8 text. The payload carries no credentials.
CREATE OR REPLACE FUNCTION payment_export_canonical_payload(
    p_payment_request_id uuid, p_export_version bigint) RETURNS text AS $$
DECLARE
    p record;
BEGIN
    SELECT id, invoice_case_id, purchase_order_id, review_snapshot_id, review_payload_hash,
           external_request_key, amount, currency
      INTO p
      FROM payment_request
     WHERE id = p_payment_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'payment request % does not exist', p_payment_request_id
            USING ERRCODE = '23503';
    END IF;
    RETURN '{"amount":' || p.amount::text
        || ',"currency":' || to_jsonb(p.currency)::text
        || ',"eventType":' || to_jsonb('PaymentRequestExportRequested'::text)::text
        || ',"exportVersion":' || p_export_version::text
        || ',"externalRequestKey":' || to_jsonb(p.external_request_key)::text
        || ',"idempotencyKey":' || to_jsonb((p.id::text || ':' || p_export_version::text))::text
        || ',"invoiceCaseId":' || to_jsonb(p.invoice_case_id::text)::text
        || ',"paymentRequestId":' || to_jsonb(p.id::text)::text
        || ',"purchaseOrderId":' || to_jsonb(p.purchase_order_id)::text
        || ',"reviewPayloadHash":' || to_jsonb(p.review_payload_hash)::text
        || ',"reviewSnapshotId":' || to_jsonb(p.review_snapshot_id::text)::text
        || '}';
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------------
-- 3. OutboxEvent: DB-bound to PaymentRequest, explicit claim/lease state machine
-- ---------------------------------------------------------------------------

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    payment_request_id uuid NOT NULL,
    invoice_case_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    export_version bigint NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    worker_id varchar(128),
    claim_token uuid,
    lease_expires_at timestamptz,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    delivered_at timestamptz,
    CONSTRAINT ck_outbox_event_aggregate_type CHECK (aggregate_type = 'PAYMENT_REQUEST'),
    CONSTRAINT ck_outbox_event_type CHECK (event_type = 'PaymentRequestExportRequested'),
    CONSTRAINT ck_outbox_event_export_version CHECK (export_version = 1),
    CONSTRAINT ck_outbox_event_status CHECK (status IN (
        'READY', 'CLAIMED', 'SENDING', 'DELIVERED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_outbox_event_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_event_payload_bounded CHECK (octet_length(payload::text) <= 4096),
    -- One event per aggregate + event type + export version (contract A).
    CONSTRAINT ux_outbox_event_aggregate_version UNIQUE (
        payment_request_id, event_type, export_version),
    -- One export idempotency key; the relay uses it as the ERP key.
    CONSTRAINT ux_outbox_event_idempotency_key UNIQUE (idempotency_key),
    -- Candidate key for the attempt ledger composite foreign key.
    CONSTRAINT ux_outbox_event_id_payment UNIQUE (id, payment_request_id),
    CONSTRAINT fk_outbox_event_payment_request
        FOREIGN KEY (payment_request_id) REFERENCES payment_request (id)
);

CREATE INDEX ix_outbox_event_claimable
    ON outbox_event (next_attempt_at, created_at)
    WHERE status = 'READY';
CREATE INDEX ix_outbox_event_lease
    ON outbox_event (status, lease_expires_at);

-- INSERT is bound to the immutable PaymentRequest: the aggregate identity, key
-- and the canonical payload/hash cannot be claimed by anything else.
CREATE OR REPLACE FUNCTION guard_outbox_event_insert() RETURNS trigger AS $$
DECLARE
    expected_text text;
    expected_hash text;
    payment record;
BEGIN
    IF NEW.aggregate_type <> 'PAYMENT_REQUEST'
        OR NEW.event_type <> 'PaymentRequestExportRequested' THEN
        RAISE EXCEPTION 'outbox event aggregate/type is not the payment export contract'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.aggregate_id <> NEW.payment_request_id THEN
        RAISE EXCEPTION 'outbox aggregate id must be the payment request id'
            USING ERRCODE = '23514';
    END IF;
    SELECT invoice_case_id, export_version INTO payment
      FROM payment_request WHERE id = NEW.payment_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'outbox event references a missing payment request %', NEW.payment_request_id
            USING ERRCODE = '23503';
    END IF;
    IF payment.invoice_case_id <> NEW.invoice_case_id
        OR payment.export_version <> NEW.export_version THEN
        RAISE EXCEPTION 'outbox event does not match the payment request case/export version'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.idempotency_key <> NEW.payment_request_id::text || ':' || NEW.export_version THEN
        RAISE EXCEPTION 'outbox idempotency key must be paymentRequestId:exportVersion'
            USING ERRCODE = '23514';
    END IF;
    expected_text := payment_export_canonical_payload(NEW.payment_request_id, NEW.export_version);
    expected_hash := encode(sha256(convert_to(expected_text, 'UTF8')), 'hex');
    IF NEW.payload_hash IS DISTINCT FROM expected_hash
        OR NEW.payload IS DISTINCT FROM expected_text::jsonb THEN
        RAISE EXCEPTION 'outbox payload/hash is not the canonical payment export payload'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> 'READY'
        OR NEW.attempt_count <> 0
        OR NEW.worker_id IS NOT NULL
        OR NEW.claim_token IS NOT NULL
        OR NEW.lease_expires_at IS NOT NULL
        OR NEW.delivered_at IS NOT NULL THEN
        RAISE EXCEPTION 'a new outbox event must start READY with no lease or attempts'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_event_insert
    BEFORE INSERT ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION guard_outbox_event_insert();

-- UPDATE may only advance delivery state. The subject/payload/hash/key/version
-- are immutable, DELETE is rejected, the state machine is DB-constrained, the
-- lease fields must be internally consistent for every state, and claim
-- identity (worker/token/attempt/delivered_at) can only change with a status
-- transition. The lease deadline itself may be adjusted (worker-owned) so
-- operational recovery and tests can expire a lease explicitly.
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
                'READY', 'DELIVERED', 'FAILED', 'RESULT_UNKNOWN'))) THEN
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

CREATE TRIGGER trg_outbox_event_guard
    BEFORE UPDATE OR DELETE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION guard_outbox_event_update();

-- ---------------------------------------------------------------------------
-- 4. Append-only delivery-attempt evidence, bound to the event + payment
-- ---------------------------------------------------------------------------

CREATE TABLE outbox_delivery_attempt (
    id uuid PRIMARY KEY,
    outbox_event_id uuid NOT NULL,
    payment_request_id uuid NOT NULL,
    claim_token uuid NOT NULL,
    worker_id varchar(128) NOT NULL,
    attempt_number integer NOT NULL,
    outcome varchar(32) NOT NULL,
    http_status integer,
    error_code varchar(64),
    detail varchar(500),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_outbox_attempt_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_outbox_attempt_outcome CHECK (outcome IN (
        'SENDING', 'ACKNOWLEDGED', 'FAILED', 'RETRY_SCHEDULED', 'RESULT_UNKNOWN', 'LEASE_EXPIRED')),
    CONSTRAINT ck_outbox_attempt_http_status CHECK (
        http_status IS NULL OR (http_status >= 100 AND http_status <= 599)),
    -- The attempt must belong to the event's own payment request.
    CONSTRAINT fk_outbox_attempt_event_payment
        FOREIGN KEY (outbox_event_id, payment_request_id)
        REFERENCES outbox_event (id, payment_request_id),
    -- At most one evidence row per event/attempt/outcome: duplicates are forged.
    CONSTRAINT ux_outbox_attempt_event_number_outcome
        UNIQUE (outbox_event_id, attempt_number, outcome)
);

CREATE INDEX ix_outbox_attempt_event ON outbox_delivery_attempt (outbox_event_id, attempt_number);

-- One attempt can have one SENDING row and at most one terminal/retry outcome:
-- conflicting results (e.g. ACKNOWLEDGED and FAILED) for the same attempt are
-- forged.
CREATE UNIQUE INDEX ux_outbox_attempt_terminal
    ON outbox_delivery_attempt (outbox_event_id, attempt_number)
    WHERE outcome <> 'SENDING';

-- The attempt ledger is append-only: it explains every send outcome and can
-- never be rewritten. No body and no credential is stored.
CREATE TRIGGER trg_outbox_delivery_attempt_immutable
    BEFORE UPDATE OR DELETE ON outbox_delivery_attempt
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- An attempt is only evidence if it matches the event's live claim. A SENDING
-- row is created while the event is still CLAIMED (before the state flips to
-- SENDING); a terminal/retry row is created while the event is SENDING and the
-- same attempt already has SENDING evidence. The HTTP status must match the
-- outcome, so a forged standalone attempt cannot be inserted.
CREATE OR REPLACE FUNCTION guard_outbox_attempt_insert() RETURNS trigger AS $$
DECLARE
    ev record;
BEGIN
    SELECT status, worker_id, claim_token, attempt_count, payment_request_id
      INTO ev
      FROM outbox_event
     WHERE id = NEW.outbox_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'attempt references a missing outbox event %', NEW.outbox_event_id
            USING ERRCODE = '23503';
    END IF;
    IF ev.payment_request_id <> NEW.payment_request_id THEN
        RAISE EXCEPTION 'attempt payment % does not match the event payment %',
            NEW.payment_request_id, ev.payment_request_id USING ERRCODE = '23514';
    END IF;

    IF NEW.outcome = 'SENDING' THEN
        IF ev.status <> 'CLAIMED' THEN
            RAISE EXCEPTION 'SENDING attempt requires a CLAIMED event (was %)', ev.status
                USING ERRCODE = '23514';
        END IF;
        IF NEW.claim_token IS DISTINCT FROM ev.claim_token
            OR NEW.worker_id IS DISTINCT FROM ev.worker_id THEN
            RAISE EXCEPTION 'SENDING attempt claim identity does not match the event'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.attempt_number <> ev.attempt_count + 1 THEN
            RAISE EXCEPTION 'SENDING attempt number % must be attempt_count + 1 (%)',
                NEW.attempt_number, ev.attempt_count + 1 USING ERRCODE = '23514';
        END IF;
        IF NEW.http_status IS NOT NULL OR NEW.error_code IS NOT NULL THEN
            RAISE EXCEPTION 'SENDING attempt must not carry an HTTP status or error code'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    -- Terminal / retry evidence.
    IF ev.status <> 'SENDING' THEN
        RAISE EXCEPTION '% attempt requires a SENDING event (was %)', NEW.outcome, ev.status
            USING ERRCODE = '23514';
    END IF;
    IF NEW.claim_token IS DISTINCT FROM ev.claim_token
        OR NEW.worker_id IS DISTINCT FROM ev.worker_id THEN
        RAISE EXCEPTION '% attempt claim identity does not match the event', NEW.outcome
            USING ERRCODE = '23514';
    END IF;
    IF NEW.attempt_number <> ev.attempt_count THEN
        RAISE EXCEPTION '% attempt number % must equal attempt_count %',
            NEW.outcome, NEW.attempt_number, ev.attempt_count USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM outbox_delivery_attempt a
             WHERE a.outbox_event_id = NEW.outbox_event_id
               AND a.attempt_number = NEW.attempt_number
               AND a.claim_token = NEW.claim_token
               AND a.outcome = 'SENDING') THEN
        RAISE EXCEPTION '% attempt has no same-attempt SENDING evidence', NEW.outcome
            USING ERRCODE = '23514';
    END IF;

    IF NEW.outcome = 'ACKNOWLEDGED' THEN
        IF NEW.http_status IS NULL OR NEW.http_status < 200 OR NEW.http_status >= 300 THEN
            RAISE EXCEPTION 'ACKNOWLEDGED attempt requires a 2xx HTTP status'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.outcome = 'RETRY_SCHEDULED' THEN
        IF NEW.http_status IS DISTINCT FROM 429 OR NEW.error_code IS DISTINCT FROM 'RATE_LIMITED' THEN
            RAISE EXCEPTION 'RETRY_SCHEDULED attempt requires HTTP 429 and RATE_LIMITED'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.outcome = 'FAILED' THEN
        IF NOT (
            (NEW.http_status BETWEEN 400 AND 499 AND NEW.http_status <> 429)
            OR (NEW.http_status = 429 AND NEW.error_code = 'RATE_LIMIT_EXHAUSTED')) THEN
            RAISE EXCEPTION 'FAILED attempt requires a non-retryable 4xx or exhausted 429'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.outcome = 'RESULT_UNKNOWN' THEN
        -- transport/lease failures have no status; a server answer (3xx redirect
        -- or 5xx) is recorded but still cannot be trusted as success/failure.
        IF NOT (NEW.http_status IS NULL OR NEW.http_status BETWEEN 300 AND 599) THEN
            RAISE EXCEPTION 'RESULT_UNKNOWN attempt requires no HTTP status, a 3xx or a 5xx'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.outcome = 'LEASE_EXPIRED' THEN
        IF NEW.http_status IS NOT NULL OR NEW.error_code IS DISTINCT FROM 'LEASE_EXPIRED' THEN
            RAISE EXCEPTION 'LEASE_EXPIRED attempt requires no HTTP status and LEASE_EXPIRED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_attempt_insert
    BEFORE INSERT ON outbox_delivery_attempt
    FOR EACH ROW EXECUTE FUNCTION guard_outbox_attempt_insert();

-- Deferred commit validation: the outbox transition and the final
-- outbox/payment/case state must be backed by the matching attempt evidence.
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
        expected_outcome := 'ACKNOWLEDGED';
        evidence_attempt := OLD.attempt_count;
        evidence_token := OLD.claim_token;
    ELSIF NEW.status = 'FAILED' THEN
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

CREATE CONSTRAINT TRIGGER trg_outbox_event_evidence
    AFTER UPDATE ON outbox_event
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_outbox_attempt_evidence();

-- Deferred commit validation on the attempt row itself: a standalone evidence
-- row cannot commit unless the final event/payment/case state is exactly the
-- tuple the outcome claims. This closes the gap where evidence is inserted
-- without the matching transition (the reverse direction of the trigger above).
CREATE OR REPLACE FUNCTION check_outbox_attempt_commit() RETURNS trigger AS $$
DECLARE
    expected_outbox varchar;
    actual_outbox varchar;
BEGIN
    IF NEW.outcome = 'SENDING' THEN
        expected_outbox := 'SENDING';
    ELSIF NEW.outcome = 'ACKNOWLEDGED' THEN
        expected_outbox := 'DELIVERED';
    ELSIF NEW.outcome = 'RETRY_SCHEDULED' THEN
        expected_outbox := 'READY';
    ELSIF NEW.outcome = 'FAILED' THEN
        expected_outbox := 'FAILED';
    ELSE
        expected_outbox := 'RESULT_UNKNOWN';  -- RESULT_UNKNOWN / LEASE_EXPIRED
    END IF;
    SELECT status INTO actual_outbox FROM outbox_event WHERE id = NEW.outbox_event_id;
    IF actual_outbox IS DISTINCT FROM expected_outbox THEN
        RAISE EXCEPTION '% evidence requires a committed % outbox (was %)',
            NEW.outcome, expected_outbox, actual_outbox USING ERRCODE = '23514';
    END IF;
    -- Delegates the full (payment, outbox, case) tuple so a standalone attempt
    -- with a matching event but inconsistent payment/case also fails.
    PERFORM assert_payment_export_consistency(NEW.payment_request_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_outbox_attempt_commit
    AFTER INSERT ON outbox_delivery_attempt
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_outbox_attempt_commit();

-- ---------------------------------------------------------------------------
-- 5. Deferred cross-state guards: the full (payment, outbox, case) tuple
-- ---------------------------------------------------------------------------

-- Allowed matrix (bidirectional):
--   NOT_SENT        <-> READY | CLAIMED   , case EXPORT_PENDING
--   RETRY_SCHEDULED <-> READY | CLAIMED   , case EXPORT_PENDING
--   SENDING         <-> SENDING           , case EXPORT_PENDING
--   ACKNOWLEDGED    <-> DELIVERED         , case EXPORTED
--   FAILED          <-> FAILED            , case EXPORT_PENDING
--   RESULT_UNKNOWN  <-> RESULT_UNKNOWN    , case EXPORT_PENDING
CREATE OR REPLACE FUNCTION assert_payment_export_consistency(p_payment_id uuid) RETURNS void AS $$
DECLARE
    p_status varchar;
    o_status varchar;
    c_status varchar;
BEGIN
    SELECT p.status, c.status, o.status
      INTO p_status, c_status, o_status
      FROM payment_request p
      JOIN invoice_case c ON c.id = p.invoice_case_id
      LEFT JOIN outbox_event o ON o.payment_request_id = p.id
     WHERE p.id = p_payment_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF o_status IS NULL THEN
        RAISE EXCEPTION 'payment % has no outbox event', p_payment_id
            USING ERRCODE = '23514';
    END IF;
    IF p_status = 'ACKNOWLEDGED' THEN
        IF o_status <> 'DELIVERED' OR c_status <> 'EXPORTED' THEN
            RAISE EXCEPTION 'ACKNOWLEDGED payment % requires DELIVERED outbox and EXPORTED case',
                p_payment_id USING ERRCODE = '23514';
        END IF;
        RETURN;
    END IF;
    -- Every non-success state forces the case back to EXPORT_PENDING.
    IF c_status <> 'EXPORT_PENDING' THEN
        RAISE EXCEPTION 'payment % in state % requires case EXPORT_PENDING (was %)',
            p_payment_id, p_status, c_status USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (p_status = 'NOT_SENT' AND o_status IN ('READY', 'CLAIMED'))
        OR (p_status = 'RETRY_SCHEDULED' AND o_status IN ('READY', 'CLAIMED'))
        OR (p_status = 'SENDING' AND o_status = 'SENDING')
        OR (p_status = 'FAILED' AND o_status = 'FAILED')
        OR (p_status = 'RESULT_UNKNOWN' AND o_status = 'RESULT_UNKNOWN')) THEN
        RAISE EXCEPTION 'inconsistent payment/outbox pair (% , %) for payment %',
            p_status, o_status, p_payment_id USING ERRCODE = '23514';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION check_payment_consistency() RETURNS trigger AS $$
BEGIN
    PERFORM assert_payment_export_consistency(NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION check_outbox_consistency() RETURNS trigger AS $$
BEGIN
    PERFORM assert_payment_export_consistency(NEW.payment_request_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Validate every payment of the case so an EXPORTED case can never coexist with
-- a non-success payment, and a non-EXPORTED case can never hold a success one.
CREATE OR REPLACE FUNCTION check_case_consistency() RETURNS trigger AS $$
DECLARE
    payment record;
    payment_count integer := 0;
BEGIN
    FOR payment IN SELECT id FROM payment_request WHERE invoice_case_id = NEW.id LOOP
        PERFORM assert_payment_export_consistency(payment.id);
        payment_count := payment_count + 1;
    END LOOP;
    IF NEW.status = 'EXPORTED' AND payment_count = 0 THEN
        RAISE EXCEPTION 'EXPORTED case % has no payment request', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_payment_request_export_consistency
    AFTER INSERT OR UPDATE ON payment_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_payment_consistency();

CREATE CONSTRAINT TRIGGER trg_outbox_event_export_consistency
    AFTER INSERT OR UPDATE ON outbox_event
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_outbox_consistency();

CREATE CONSTRAINT TRIGGER trg_invoice_case_export_consistency
    AFTER UPDATE ON invoice_case
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_case_consistency();

-- ---------------------------------------------------------------------------
-- 6. Backfill one READY event (export version 1) per pre-existing payment
-- ---------------------------------------------------------------------------

-- Deterministic idempotency key and canonical payload/hash for every V7
-- PaymentRequest that has no event yet. The insert trigger re-validates the
-- canonical payload, so the backfill and the application writer share the exact
-- same contract. The deferred guards re-check the whole set at commit.
INSERT INTO outbox_event (
    id, aggregate_type, aggregate_id, payment_request_id, invoice_case_id,
    event_type, export_version, idempotency_key, payload, payload_hash,
    status, attempt_count, created_at, updated_at)
SELECT
    gen_random_uuid(),
    'PAYMENT_REQUEST',
    pr.id,
    pr.id,
    pr.invoice_case_id,
    'PaymentRequestExportRequested',
    1,
    pr.id::text || ':1',
    payment_export_canonical_payload(pr.id, 1)::jsonb,
    encode(sha256(convert_to(payment_export_canonical_payload(pr.id, 1), 'UTF8')), 'hex'),
    'READY',
    0,
    pr.created_at,
    clock_timestamp()
FROM payment_request pr
WHERE NOT EXISTS (
    SELECT 1 FROM outbox_event o WHERE o.payment_request_id = pr.id);
