-- AI inputs and outputs are separate from parser results and business decisions.
CREATE TABLE proposal_run (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case(id),
    evidence_bundle_id uuid NOT NULL,
    parser_run_id uuid NOT NULL REFERENCES analysis_run(id),
    match_result_id uuid NOT NULL,
    context_hash varchar(64) NOT NULL CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    context jsonb NOT NULL CHECK (jsonb_typeof(context)='object' AND octet_length(context::text)<=262144),
    workflow_version varchar(32) NOT NULL CHECK (workflow_version='ai-review-v1'),
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','STALE')),
    execution_token uuid,
    lease_until timestamptz,
    execution_attempt integer NOT NULL DEFAULT 0 CHECK (execution_attempt BETWEEN 0 AND 3),
    reserved_calls integer NOT NULL DEFAULT 0 CHECK (reserved_calls BETWEEN 0 AND 5),
    reserved_tokens integer NOT NULL DEFAULT 0 CHECK (reserved_tokens BETWEEN 0 AND 40000),
    tool_calls integer NOT NULL DEFAULT 0 CHECK (tool_calls BETWEEN 0 AND 8),
    error_code varchar(64),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (parser_run_id,match_result_id,workflow_version),
    UNIQUE (id,invoice_case_id,evidence_bundle_id,match_result_id,context_hash),
    FOREIGN KEY (match_result_id,invoice_case_id,evidence_bundle_id)
        REFERENCES match_result(id,invoice_case_id,evidence_bundle_id),
    CHECK ((status='RUNNING')=(execution_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status='RUNNING' OR (execution_token IS NULL AND lease_until IS NULL))
);
ALTER TABLE analysis_run ADD CONSTRAINT ux_analysis_run_id_case_bundle UNIQUE(id,invoice_case_id,evidence_bundle_id);
ALTER TABLE proposal_run ADD CONSTRAINT fk_proposal_parser_same_input
    FOREIGN KEY(parser_run_id,invoice_case_id,evidence_bundle_id)
    REFERENCES analysis_run(id,invoice_case_id,evidence_bundle_id);
CREATE INDEX ix_proposal_run_case ON proposal_run(invoice_case_id,created_at DESC,id);

CREATE TABLE proposal_step (
    run_id uuid NOT NULL REFERENCES proposal_run(id),
    stage varchar(80) NOT NULL,
    payload jsonb NOT NULL CHECK (octet_length(payload::text)<=131072),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,stage)
);
CREATE TRIGGER trg_proposal_step_immutable BEFORE UPDATE OR DELETE ON proposal_step
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE proposal_call_reservation (
    run_id uuid NOT NULL REFERENCES proposal_run(id),
    request_id uuid NOT NULL,
    reserved_tokens integer NOT NULL CHECK(reserved_tokens BETWEEN 1 AND 40000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,request_id)
);
CREATE TRIGGER trg_proposal_call_immutable BEFORE UPDATE OR DELETE ON proposal_call_reservation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE proposal (
    id uuid PRIMARY KEY REFERENCES proposal_run(id),
    invoice_case_id uuid NOT NULL,
    evidence_bundle_id uuid NOT NULL,
    match_result_id uuid NOT NULL,
    context_hash varchar(64) NOT NULL,
    payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=131072),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(id,invoice_case_id,evidence_bundle_id,match_result_id,context_hash)
        REFERENCES proposal_run(id,invoice_case_id,evidence_bundle_id,match_result_id,context_hash)
);
CREATE TRIGGER trg_proposal_immutable BEFORE UPDATE OR DELETE ON proposal
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE proposal_request_outbox (
    id uuid PRIMARY KEY REFERENCES proposal_run(id),
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('READY','CLAIMED','PUBLISHED','CANCELLED')),
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    published_at timestamptz,
    CHECK ((status='CLAIMED')=(lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status='CLAIMED' OR (lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((status='PUBLISHED')=(published_at IS NOT NULL))
);
CREATE INDEX ix_proposal_outbox_due ON proposal_request_outbox(next_attempt_at) WHERE status='READY';

CREATE FUNCTION guard_proposal_run() RETURNS trigger AS $$
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
CREATE TRIGGER trg_proposal_run_guard BEFORE UPDATE OR DELETE ON proposal_run
    FOR EACH ROW EXECUTE FUNCTION guard_proposal_run();

CREATE FUNCTION guard_proposal_outbox() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.id IS DISTINCT FROM OLD.id OR NEW.payload IS DISTINCT FROM OLD.payload THEN
        RAISE EXCEPTION 'proposal event immutable' USING ERRCODE='23000';
    END IF;
    IF OLD.status IN ('PUBLISHED','CANCELLED') THEN
        RAISE EXCEPTION 'terminal proposal event' USING ERRCODE='23000';
    END IF;
    IF NEW.status='CANCELLED' THEN RETURN NEW; END IF;
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
CREATE TRIGGER trg_proposal_outbox_guard BEFORE UPDATE OR DELETE ON proposal_request_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_proposal_outbox();

ALTER TABLE audit_entry DROP CONSTRAINT ck_audit_entry_action;
ALTER TABLE audit_entry ADD CONSTRAINT ck_audit_entry_action CHECK (action IN (
    'CASE_CREATED','DRAFT_LINES_REPLACED','CASE_SUBMITTED','SUPPLEMENT_REVISION_OPENED',
    'MATCH_RUN','REVIEW_SNAPSHOT_FROZEN','ITEM_MAPPED','SUPPLEMENT_REQUESTED','CASE_REJECTED','APPROVE',
    'DOCUMENT_UPLOAD_RESERVED','DOCUMENT_REGISTERED','ANALYSIS_RETRY_RESERVED','AI_ANALYSIS_RESERVED'));
