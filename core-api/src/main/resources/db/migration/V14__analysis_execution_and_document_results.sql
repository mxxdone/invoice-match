-- P2-07 analysis execution claim/lease and per-document result reflection.
--
-- V12/V13 reserved and published one analysis request per frozen document
-- bundle. V14 adds the execution lifecycle a machine worker drives after it
-- consumes that request:
--
--   analysis_run.status
--     QUEUED   -> RUNNING                 (claim)
--     RUNNING  -> COMPLETED | FAILED      (last document result, in the same TX)
--     QUEUED | RUNNING | COMPLETED | FAILED -> STALE   (newer bundle submitted)
--   RUNNING -> RUNNING                    (expired reclaim / heartbeat extension)
--
-- RUNNING carries a non-null execution token and lease; every other state
-- carries neither. execution_attempt is non-negative and only advances on a
-- claim (a fresh QUEUED claim or an expired RUNNING reclaim), never on a
-- heartbeat. All lease deadlines and expiry comparisons use PostgreSQL
-- clock_timestamp(), so separate nodes cannot disagree about time.
--
-- V13 is not modified. Existing run identity, frozen hash, workflow, and the
-- append-only/DELETE protections are preserved; only the status domain and the
-- execution metadata expand.
--
-- analysis_document_result is the immutable per-document parse outcome. It is
-- append-only: a completed result is never rewritten, and a result that is
-- already terminal is replayed rather than re-inserted.

ALTER TABLE analysis_run
    ADD COLUMN execution_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN execution_attempt integer NOT NULL DEFAULT 0;

ALTER TABLE analysis_run
    DROP CONSTRAINT ck_analysis_run_status;

ALTER TABLE analysis_run
    ADD CONSTRAINT ck_analysis_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'STALE'));

ALTER TABLE analysis_run
    ADD CONSTRAINT ck_analysis_run_execution_attempt CHECK (execution_attempt >= 0);

-- Every execution state/field combination is constrained so a partially
-- claimed, a terminal-with-lease, or a queued-with-token run cannot exist.
ALTER TABLE analysis_run
    ADD CONSTRAINT ck_analysis_run_execution CHECK (
        (status = 'RUNNING'
            AND execution_token IS NOT NULL
            AND lease_until IS NOT NULL)
        OR (status IN ('QUEUED', 'COMPLETED', 'FAILED', 'STALE')
            AND execution_token IS NULL
            AND lease_until IS NULL));

-- Replaces the V12 guard: the state machine is expanded, the execution attempt
-- may only advance by a precise claim, and a stale token can never rewrite a
-- current claim or a terminal row. Identity/hash/workflow stay immutable.
--
-- Exact claim arithmetic enforced here (all deadlines are DB clock):
--   QUEUED  -> RUNNING : attempt + 1, new future lease
--   RUNNING -> RUNNING : either a no-op of the claim fields (updated_at only),
--                        a same-token heartbeat (attempt unchanged, old lease
--                        unexpired, new lease strictly extending), or a
--                        different-token reclaim (old lease expired, attempt + 1,
--                        new future lease)
--   RUNNING -> COMPLETED|FAILED, * -> STALE : attempt unchanged, claim dropped
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
    IF NEW.execution_attempt < OLD.execution_attempt THEN
        RAISE EXCEPTION 'analysis_run execution_attempt must not decrease'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF OLD.status = 'QUEUED' AND NEW.status = 'RUNNING' THEN
            IF NEW.execution_attempt <> OLD.execution_attempt + 1
                OR NEW.lease_until IS NULL
                OR NEW.lease_until <= clock_timestamp() THEN
                RAISE EXCEPTION 'a QUEUED claim must set attempt+1 and a future lease'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED') THEN
            IF NEW.execution_attempt <> OLD.execution_attempt
                OR NEW.execution_token IS NOT NULL
                OR NEW.lease_until IS NOT NULL THEN
                RAISE EXCEPTION 'a terminal analysis_run must keep the attempt and drop the claim'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED') AND NEW.status = 'STALE' THEN
            -- Lease expiration must never prevent superseding a run.
            IF NEW.execution_attempt <> OLD.execution_attempt
                OR NEW.execution_token IS NOT NULL
                OR NEW.lease_until IS NOT NULL THEN
                RAISE EXCEPTION 'a STALE analysis_run must keep the attempt and drop the claim'
                    USING ERRCODE = '23514';
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

CREATE INDEX ix_analysis_run_execution_lease
    ON analysis_run (status, lease_until);

CREATE TABLE analysis_document_result (
    run_id uuid NOT NULL,
    document_id uuid NOT NULL,
    source_checksum varchar(128) NOT NULL,
    outcome varchar(16) NOT NULL,
    parser_version varchar(128) NOT NULL,
    result_schema_version varchar(64) NOT NULL,
    payload jsonb,
    error_code varchar(64),
    payload_hash varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (run_id, document_id),
    CONSTRAINT fk_analysis_document_result_run
        FOREIGN KEY (run_id) REFERENCES analysis_run (id),
    CONSTRAINT ck_analysis_document_result_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    -- A success carries the parse result and no error; a failure carries only a
    -- fixed classification code and no parser content.
    CONSTRAINT ck_analysis_document_result_payload CHECK (
        (outcome = 'SUCCESS' AND payload IS NOT NULL AND error_code IS NULL)
        OR (outcome = 'FAILURE' AND payload IS NULL AND error_code IS NOT NULL))
);

-- Results are append-only: UPDATE and DELETE are rejected at the database.
CREATE OR REPLACE FUNCTION guard_analysis_document_result_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'analysis_document_result is append-only and does not allow %', TG_OP
        USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_analysis_document_result_immutable
    BEFORE UPDATE OR DELETE ON analysis_document_result
    FOR EACH ROW EXECUTE FUNCTION guard_analysis_document_result_mutation();
