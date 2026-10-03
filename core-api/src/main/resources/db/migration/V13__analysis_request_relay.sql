-- P2-06 analysis-request RabbitMQ publisher relay.
--
-- V12 reserved one READY analysis-request Outbox row per enabled document
-- submission. V13 adds the bounded lease/send lifecycle and the due-query
-- index. The relay claims one due READY row at a time with a DB-time lease,
-- publishes it with publisher confirm + mandatory return, and only then marks
-- it PUBLISHED. A superseded request is CANCELLED with a conditional update so
-- dirty checking can never overwrite a CLAIMED/PUBLISHED row.
--
-- State machine added here (P2-05 had only READY -> CANCELLED):
--   READY   -> CLAIMED                   (claim)
--   CLAIMED -> PUBLISHED                 (confirm ACK and no return)
--   CLAIMED -> READY                     (release after failure / lease recovery)
--   READY   -> CANCELLED                 (superseded, conditional)
--   CLAIMED -> CANCELLED                 (superseded while in flight)
-- PUBLISHED and CANCELLED are terminal.
--
-- Invariants:
--   * CLAIMED  carries a non-null claim_token and lease_until, no published_at.
--   * PUBLISHED carries published_at, no claim_token/lease_until.
--   * READY/CANCELLED carry neither.
--   * next_attempt_at is always non-null; attempt_count is non-negative and
--     never decreases.
--   * Subject, schema and payload stay immutable; DELETE stays rejected.
--
-- Existing READY rows are backfilled with next_attempt_at = created_at, so a
-- V12 database with committed reservations is immediately due. The identity,
-- schema, payload, createdAt and delete protection from V12 are preserved.
-- V12 is not modified.

ALTER TABLE analysis_request_outbox
    ADD COLUMN claim_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN published_at timestamptz,
    ADD COLUMN last_error_code varchar(64);

UPDATE analysis_request_outbox
    SET next_attempt_at = created_at
    WHERE next_attempt_at IS NULL;

ALTER TABLE analysis_request_outbox
    ALTER COLUMN next_attempt_at SET NOT NULL;

-- New reservations are due immediately; the application also sets this
-- explicitly, and a raw insert without it must still satisfy the NOT NULL
-- contract (existing rows were backfilled to created_at above).
ALTER TABLE analysis_request_outbox
    ALTER COLUMN next_attempt_at SET DEFAULT clock_timestamp();

ALTER TABLE analysis_request_outbox
    DROP CONSTRAINT ck_analysis_request_outbox_status;

ALTER TABLE analysis_request_outbox
    ADD CONSTRAINT ck_analysis_request_outbox_status CHECK (
        status IN ('READY', 'CLAIMED', 'PUBLISHED', 'CANCELLED'));

ALTER TABLE analysis_request_outbox
    ADD CONSTRAINT ck_analysis_request_outbox_attempt_count CHECK (attempt_count >= 0);

-- Every state/lease field combination is constrained so a partially-claimed or
-- terminal row cannot exist.
ALTER TABLE analysis_request_outbox
    ADD CONSTRAINT ck_analysis_request_outbox_lease CHECK (
        (status = 'CLAIMED'
            AND claim_token IS NOT NULL
            AND lease_until IS NOT NULL
            AND published_at IS NULL)
        OR (status = 'PUBLISHED'
            AND claim_token IS NULL
            AND lease_until IS NULL
            AND published_at IS NOT NULL)
        OR (status IN ('READY', 'CANCELLED')
            AND claim_token IS NULL
            AND lease_until IS NULL
            AND published_at IS NULL));

-- Due lookup for the single-row claim.
CREATE INDEX ix_analysis_request_outbox_due
    ON analysis_request_outbox (next_attempt_at, created_at)
    WHERE status = 'READY';

-- Lease recovery lookup.
CREATE INDEX ix_analysis_request_outbox_lease
    ON analysis_request_outbox (status, lease_until);

-- Replaces the V12 guard: the state machine is expanded and attempt metadata is
-- protected. Claim identity can only change with a status transition, so a
-- stale token can never rewrite a current claim or a terminal row.
CREATE OR REPLACE FUNCTION guard_analysis_request_outbox_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'analysis_request_outbox % is not deletable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'analysis_request_outbox subject, schema and payload are immutable'
            USING ERRCODE = '23000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'analysis_request_outbox attempt_count must not decrease'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'READY' AND NEW.status = 'CLAIMED')
            OR (OLD.status = 'CLAIMED' AND NEW.status IN ('PUBLISHED', 'READY'))
            OR (OLD.status IN ('READY', 'CLAIMED') AND NEW.status = 'CANCELLED')) THEN
            RAISE EXCEPTION 'illegal analysis_request_outbox status transition % -> %',
                OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    ELSE
        -- Same-state updates may not mutate any claim identity at all: the lease
        -- deadline is strictly worker-owned only through a status transition, so
        -- a stale token cannot extend or steal a live claim.
        IF NEW.claim_token IS DISTINCT FROM OLD.claim_token
            OR NEW.lease_until IS DISTINCT FROM OLD.lease_until
            OR NEW.published_at IS DISTINCT FROM OLD.published_at
            OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count THEN
            RAISE EXCEPTION 'analysis request claim identity cannot change without a status transition'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_analysis_request_outbox_mutation
    BEFORE UPDATE OR DELETE ON analysis_request_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_analysis_request_outbox_mutation();
