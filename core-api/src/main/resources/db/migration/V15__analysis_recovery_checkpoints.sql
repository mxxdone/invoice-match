-- Durable recovery checkpoints. Initial request identity and document results remain immutable.
ALTER TABLE analysis_run ADD COLUMN attempt_limit integer NOT NULL DEFAULT 3 CHECK (attempt_limit > 0);
UPDATE analysis_run SET attempt_limit=greatest(3,execution_attempt);
ALTER TABLE analysis_run DROP CONSTRAINT ck_analysis_run_status;
ALTER TABLE analysis_run ADD CONSTRAINT ck_analysis_run_status CHECK (status IN
 ('QUEUED','RUNNING','COMPLETED','FAILED','STALE','RETRY_SCHEDULED','DEAD_LETTERED'));
ALTER TABLE analysis_run DROP CONSTRAINT ck_analysis_run_execution;
ALTER TABLE analysis_run ADD CONSTRAINT ck_analysis_run_execution CHECK (
 (status='RUNNING' AND execution_token IS NOT NULL AND lease_until IS NOT NULL)
 OR (status <> 'RUNNING' AND execution_token IS NULL AND lease_until IS NULL));
CREATE OR REPLACE FUNCTION guard_analysis_run_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'analysis_run % is not deletable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.invoice_case_id IS DISTINCT FROM OLD.invoice_case_id
        OR NEW.evidence_bundle_id IS DISTINCT FROM OLD.evidence_bundle_id
        OR NEW.input_version IS DISTINCT FROM OLD.input_version
        OR NEW.evidence_payload_hash IS DISTINCT FROM OLD.evidence_payload_hash
        OR NEW.workflow_version IS DISTINCT FROM OLD.workflow_version
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'analysis_run input identity, hash and workflow are immutable'
            USING ERRCODE = '23000';
    END IF;
    IF NEW.attempt_limit IS DISTINCT FROM OLD.attempt_limit
        AND NOT (OLD.status = 'DEAD_LETTERED' AND NEW.status = 'QUEUED' AND NEW.attempt_limit = OLD.execution_attempt + 3) THEN
        RAISE EXCEPTION 'attempt budget can only change on operator retry' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'RUNNING' AND NEW.execution_attempt > NEW.attempt_limit THEN
        RAISE EXCEPTION 'analysis attempt budget exhausted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'RETRY_SCHEDULED' AND NEW.status = 'RUNNING'
        AND NOT EXISTS (
            SELECT 1 FROM analysis_execution_failure f
            JOIN analysis_recovery_dispatch o ON o.analysis_run_id=f.run_id
                AND o.dedup_key='failure:'||f.claim_token::text AND o.destination='REQUEST'
            WHERE f.run_id=OLD.id AND f.execution_attempt=OLD.execution_attempt
                AND o.next_attempt_at<=clock_timestamp()) THEN
        RAISE EXCEPTION 'retry checkpoint is not due' USING ERRCODE = '23514';
    END IF;
    IF NEW.execution_attempt < OLD.execution_attempt THEN
        RAISE EXCEPTION 'analysis_run execution_attempt must not decrease'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF OLD.status IN ('QUEUED', 'RETRY_SCHEDULED') AND NEW.status = 'RUNNING' THEN
            IF NEW.execution_attempt <> OLD.execution_attempt + 1
                OR NEW.lease_until IS NULL
                OR NEW.lease_until <= clock_timestamp() THEN
                RAISE EXCEPTION 'a QUEUED claim must set attempt+1 and a future lease'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED', 'RETRY_SCHEDULED', 'DEAD_LETTERED') THEN
            IF NEW.execution_attempt <> OLD.execution_attempt
                OR NEW.execution_token IS NOT NULL
                OR NEW.lease_until IS NOT NULL THEN
                RAISE EXCEPTION 'a terminal analysis_run must keep the attempt and drop the claim'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'RETRY_SCHEDULED', 'DEAD_LETTERED') AND NEW.status = 'STALE' THEN
            -- Lease expiration must never prevent superseding a run.
            IF NEW.execution_attempt <> OLD.execution_attempt
                OR NEW.execution_token IS NOT NULL
                OR NEW.lease_until IS NOT NULL THEN
                RAISE EXCEPTION 'a STALE analysis_run must keep the attempt and drop the claim'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status = 'DEAD_LETTERED' AND NEW.status = 'QUEUED' THEN
            IF NEW.execution_attempt <> OLD.execution_attempt OR NEW.attempt_limit <> OLD.execution_attempt + 3 THEN
                RAISE EXCEPTION 'operator retry must preserve attempts and grant exactly three more' USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'illegal analysis_run status transition % -> %', OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status = 'RUNNING' THEN
        IF NEW.execution_token IS NOT DISTINCT FROM OLD.execution_token
            AND NEW.execution_attempt = OLD.execution_attempt
            AND NEW.lease_until IS NOT DISTINCT FROM OLD.lease_until THEN
            -- No-op of the claim fields (for example an updated_at-only touch).
            NULL;
        ELSIF NEW.execution_token IS NOT DISTINCT FROM OLD.execution_token THEN
            -- Same token: heartbeat. Attempt unchanged, old lease still live and
            -- the new lease must strictly extend it.
            IF NEW.execution_attempt <> OLD.execution_attempt
                OR OLD.lease_until <= clock_timestamp()
                OR NEW.lease_until IS NULL
                OR NEW.lease_until <= OLD.lease_until
                OR NEW.lease_until <= clock_timestamp() THEN
                RAISE EXCEPTION 'a heartbeat must keep the attempt and extend a live lease'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            -- Different token: reclaim. Only an expired old lease may be
            -- reclaimed, with attempt+1 and a future new lease.
            IF OLD.lease_until > clock_timestamp()
                OR NEW.execution_attempt <> OLD.execution_attempt + 1
                OR NEW.lease_until IS NULL
                OR NEW.lease_until <= clock_timestamp() THEN
                RAISE EXCEPTION 'a reclaim requires an expired lease, attempt+1 and a future lease'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSE
        -- Non-running same-state updates may not mutate the claim identity.
        IF NEW.execution_token IS DISTINCT FROM OLD.execution_token
            OR NEW.lease_until IS DISTINCT FROM OLD.lease_until
            OR NEW.execution_attempt IS DISTINCT FROM OLD.execution_attempt THEN
            RAISE EXCEPTION 'analysis_run execution identity cannot change without a status transition'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_analysis_run_mutation
    BEFORE UPDATE OR DELETE ON analysis_run
    FOR EACH ROW EXECUTE FUNCTION guard_analysis_run_mutation();


CREATE TABLE analysis_execution_failure (
 run_id uuid NOT NULL REFERENCES analysis_run(id), claim_token uuid NOT NULL,
 execution_attempt integer NOT NULL CHECK(execution_attempt > 0), error_code varchar(64) NOT NULL,
 disposition varchar(32) NOT NULL CHECK(disposition IN ('RETRY_SCHEDULED','DEAD_LETTERED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(run_id,claim_token));
CREATE TRIGGER trg_analysis_failure_immutable BEFORE UPDATE OR DELETE ON analysis_execution_failure
 FOR EACH ROW EXECUTE FUNCTION guard_analysis_document_result_mutation();
ALTER TABLE analysis_request_outbox ADD CONSTRAINT uq_analysis_event_run UNIQUE(id,analysis_run_id);
CREATE TABLE analysis_recovery_dispatch (
 id uuid PRIMARY KEY, analysis_run_id uuid NOT NULL REFERENCES analysis_run(id),
 event_id uuid NOT NULL REFERENCES analysis_request_outbox(id), dedup_key varchar(128) NOT NULL,
 destination varchar(16) NOT NULL CHECK(destination IN ('REQUEST','DLQ')),
 schema_version varchar(64) NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','CLAIMED','PUBLISHED','CANCELLED')),
 next_attempt_at timestamptz NOT NULL, claim_token uuid, lease_until timestamptz, published_at timestamptz,
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count >= 0), last_error_code varchar(64),
 FOREIGN KEY(event_id,analysis_run_id) REFERENCES analysis_request_outbox(id,analysis_run_id),
 UNIQUE(analysis_run_id,dedup_key),
 CHECK((status='CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND published_at IS NULL)
 OR (status='PUBLISHED' AND claim_token IS NULL AND lease_until IS NULL AND published_at IS NOT NULL)
 OR (status IN ('READY','CANCELLED') AND claim_token IS NULL AND lease_until IS NULL AND published_at IS NULL)));
CREATE INDEX ix_analysis_recovery_due ON analysis_recovery_dispatch(next_attempt_at,created_at) WHERE status='READY';
CREATE OR REPLACE FUNCTION guard_analysis_recovery_dispatch_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'analysis_recovery_dispatch % is not deletable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.analysis_run_id IS DISTINCT FROM OLD.analysis_run_id
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.destination IS DISTINCT FROM OLD.destination
        OR NEW.dedup_key IS DISTINCT FROM OLD.dedup_key THEN
        RAISE EXCEPTION 'analysis_recovery_dispatch subject, schema and payload are immutable'
            USING ERRCODE = '23000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'analysis_recovery_dispatch attempt_count must not decrease'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'CLAIMED' AND OLD.status = 'READY' THEN
        IF NEW.attempt_count <> OLD.attempt_count + 1 OR NEW.lease_until <= clock_timestamp() THEN
            RAISE EXCEPTION 'recovery claim requires attempt+1 and a future lease' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.attempt_count IS DISTINCT FROM OLD.attempt_count THEN
        RAISE EXCEPTION 'recovery attempts only advance on claim' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PUBLISHED' AND OLD.status = 'CLAIMED' AND OLD.lease_until <= clock_timestamp() THEN
        RAISE EXCEPTION 'expired recovery claim cannot finalize' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = OLD.status AND OLD.status <> 'READY'
        AND (NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at
             OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code) THEN
        RAISE EXCEPTION 'terminal recovery metadata is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'READY' AND NEW.status = 'CLAIMED')
            OR (OLD.status = 'CLAIMED' AND NEW.status IN ('PUBLISHED', 'READY'))
            OR (OLD.status IN ('READY', 'CLAIMED') AND NEW.status = 'CANCELLED')) THEN
            RAISE EXCEPTION 'illegal analysis_recovery_dispatch status transition % -> %',
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

CREATE OR REPLACE TRIGGER trg_analysis_recovery_dispatch_mutation
    BEFORE UPDATE OR DELETE ON analysis_recovery_dispatch
    FOR EACH ROW EXECUTE FUNCTION guard_analysis_recovery_dispatch_mutation();
