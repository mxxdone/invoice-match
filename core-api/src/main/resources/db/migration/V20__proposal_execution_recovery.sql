-- Preserve uncertainty, partial results and bounded execution across delivery retries.
CREATE TABLE proposal_failure (
    run_id uuid NOT NULL REFERENCES proposal_run(id),
    execution_token uuid NOT NULL,
    attempt integer NOT NULL CHECK(attempt BETWEEN 1 AND 3),
    error_code varchar(64) NOT NULL,
    disposition varchar(16) NOT NULL CHECK(disposition IN ('QUEUED','FAILED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,execution_token)
);
CREATE TRIGGER trg_proposal_failure_immutable BEFORE UPDATE OR DELETE ON proposal_failure
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TABLE proposal_dispatch (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES proposal_run(id),
    dedup_key varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','CLAIMED','PUBLISHED','CANCELLED')),
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id,dedup_key),
    CHECK ((status='CLAIMED')=(lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status='CLAIMED' OR (lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((status='PUBLISHED')=(published_at IS NOT NULL))
);
CREATE INDEX ix_proposal_dispatch_due ON proposal_dispatch(next_attempt_at) WHERE status IN ('READY','CLAIMED');
CREATE OR REPLACE FUNCTION guard_proposal_run() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR ROW(NEW.id,NEW.invoice_case_id,NEW.evidence_bundle_id,NEW.parser_run_id,
        NEW.match_result_id,NEW.context_hash,NEW.context,NEW.workflow_version,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.invoice_case_id,OLD.evidence_bundle_id,OLD.parser_run_id,
        OLD.match_result_id,OLD.context_hash,OLD.context,OLD.workflow_version,OLD.created_at) THEN
        RAISE EXCEPTION 'proposal input is immutable' USING ERRCODE='23000';
    END IF;
    IF NEW.reserved_calls<OLD.reserved_calls OR NEW.reserved_tokens<OLD.reserved_tokens
        OR NEW.tool_calls<OLD.tool_calls THEN
        RAISE EXCEPTION 'proposal budget cannot reset' USING ERRCODE='23514';
    END IF;
    IF NEW.status='STALE' AND OLD.status<>'STALE' THEN
        IF NEW.execution_attempt<>OLD.execution_attempt OR NEW.reserved_calls<>OLD.reserved_calls
            OR NEW.reserved_tokens<>OLD.reserved_tokens OR NEW.tool_calls<>OLD.tool_calls THEN
            RAISE EXCEPTION 'stale cannot mutate execution budget' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('STALE','FAILED','COMPLETED') THEN
        RAISE EXCEPTION 'terminal proposal run' USING ERRCODE='23000';
    END IF;
    IF OLD.status='RUNNING' AND OLD.lease_until<=clock_timestamp() AND OLD.execution_attempt=3 AND NEW.status='FAILED' THEN
        IF NEW.execution_attempt<>OLD.execution_attempt OR NEW.reserved_calls<>OLD.reserved_calls
            OR NEW.reserved_tokens<>OLD.reserved_tokens OR NEW.tool_calls<>OLD.tool_calls
            OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at OR NEW.error_code<>'LEASE_EXPIRED' THEN
            RAISE EXCEPTION 'invalid exhausted proposal' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status='RUNNING' AND (OLD.status='QUEUED' OR OLD.lease_until<=clock_timestamp()) THEN
        IF NEW.execution_attempt<>OLD.execution_attempt+1 OR NEW.execution_token IS NOT DISTINCT FROM OLD.execution_token
            OR NEW.lease_until<=clock_timestamp() OR OLD.next_attempt_at>clock_timestamp()
            OR NEW.reserved_calls<>OLD.reserved_calls OR NEW.reserved_tokens<>OLD.reserved_tokens
            OR NEW.tool_calls<>OLD.tool_calls THEN
            RAISE EXCEPTION 'invalid proposal claim' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.status='RUNNING' AND OLD.lease_until>clock_timestamp() THEN
        IF NEW.execution_attempt<>OLD.execution_attempt
            OR (NEW.status='RUNNING' AND (NEW.execution_token IS DISTINCT FROM OLD.execution_token
                OR NEW.lease_until<OLD.lease_until)) OR NEW.status NOT IN ('RUNNING','QUEUED','FAILED','COMPLETED') THEN
            RAISE EXCEPTION 'invalid proposal ownership' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'inactive proposal claim' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE FUNCTION guard_proposal_dispatch() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR ROW(NEW.id,NEW.run_id,NEW.dedup_key,NEW.payload,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.run_id,OLD.dedup_key,OLD.payload,OLD.created_at) THEN
        RAISE EXCEPTION 'proposal event immutable' USING ERRCODE='23000';
    END IF;
    IF OLD.status IN ('PUBLISHED','CANCELLED') THEN
        RAISE EXCEPTION 'terminal proposal event' USING ERRCODE='23000';
    END IF;
    IF NEW.status='CANCELLED' THEN
        IF NEW.attempt_count<>OLD.attempt_count OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at THEN
            RAISE EXCEPTION 'cancellation cannot mutate event history' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status='CLAIMED' AND (OLD.status='READY' OR OLD.lease_until<=clock_timestamp()) THEN
        IF NEW.attempt_count<>OLD.attempt_count+1 OR NEW.lease_until<=clock_timestamp()
            OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token OR OLD.next_attempt_at>clock_timestamp() THEN
            RAISE EXCEPTION 'invalid event claim' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.status='CLAIMED' AND OLD.lease_until>clock_timestamp() AND NEW.status IN ('READY','PUBLISHED') THEN
        IF NEW.attempt_count<>OLD.attempt_count THEN
            RAISE EXCEPTION 'invalid event settlement' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid event transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_proposal_dispatch_guard BEFORE UPDATE OR DELETE ON proposal_dispatch
    FOR EACH ROW EXECUTE FUNCTION guard_proposal_dispatch();
