-- ai-review-v2 is isolated from the immutable v1 proposal/parser contracts.
CREATE FUNCTION graph_json_allowed(value jsonb, allow_interrupt boolean, depth integer DEFAULT 0)
RETURNS boolean IMMUTABLE LANGUAGE plpgsql AS $$
DECLARE tag text; body jsonb; child jsonb;
BEGIN
    IF depth>64 OR value IS NULL OR jsonb_typeof(value)<>'array' THEN RETURN false; END IF;
    IF jsonb_array_length(value)<>2 OR jsonb_typeof(value->0)<>'string' THEN RETURN false; END IF;
    tag=value->>0; body=value->1;
    IF tag='scalar' THEN RETURN jsonb_typeof(body) IN ('string','number','boolean','null'); END IF;
    IF tag IN ('list','tuple') AND jsonb_typeof(body)='array' THEN
        FOR child IN SELECT jsonb_array_elements(body) LOOP
            IF NOT graph_json_allowed(child,allow_interrupt,depth+1) THEN RETURN false; END IF;
        END LOOP;
        RETURN true;
    END IF;
    IF tag='dict' AND jsonb_typeof(body)='object' THEN
        FOR child IN SELECT v FROM jsonb_each(body) AS e(k,v) LOOP
            IF NOT graph_json_allowed(child,allow_interrupt,depth+1) THEN RETURN false; END IF;
        END LOOP;
        RETURN true;
    END IF;
    IF tag='interrupt' AND allow_interrupt AND jsonb_typeof(body)='object'
        AND (SELECT count(*) FROM jsonb_object_keys(body))=2 AND jsonb_typeof(body->'id')='string'
        AND body ? 'value' THEN RETURN graph_json_allowed(body->'value',false,depth+1); END IF;
    RETURN false;
END $$;
CREATE TABLE graph_run (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case(id),
    case_version bigint NOT NULL CHECK(case_version>=0),
    evidence_bundle_id uuid NOT NULL,
    parser_run_id uuid NOT NULL,
    match_result_id uuid NOT NULL,
    context jsonb NOT NULL CHECK(jsonb_typeof(context)='object' AND octet_length(context::text)<=262144),
    context_hash varchar(64) NOT NULL CHECK(context_hash ~ '^[0-9a-f]{64}$'),
    workflow_version varchar(32) NOT NULL CHECK(workflow_version='ai-review-v2'),
    graph_version varchar(40) NOT NULL CHECK(graph_version='invoice-review-graph-v1'),
    serializer_version varchar(40) NOT NULL CHECK(serializer_version='graph-checkpoint-json-v1'),
    checkpoint_schema integer NOT NULL CHECK(checkpoint_schema=2),
    status varchar(20) NOT NULL CHECK(status IN ('QUEUED','RUNNING','WAITING_HUMAN','COMPLETED','FAILED','STALE')),
    active_segment varchar(8) NOT NULL DEFAULT 'START' CHECK(active_segment IN ('START','RESUME')),
    start_attempts integer NOT NULL DEFAULT 0 CHECK(start_attempts BETWEEN 0 AND 3),
    resume_attempts integer NOT NULL DEFAULT 0 CHECK(resume_attempts BETWEEN 0 AND 3),
    execution_token uuid,
    lease_until timestamptz,
    reserved_calls integer NOT NULL DEFAULT 0 CHECK(reserved_calls BETWEEN 0 AND 5),
    reserved_tokens integer NOT NULL DEFAULT 0 CHECK(reserved_tokens BETWEEN 0 AND 40000),
    tool_calls integer NOT NULL DEFAULT 0 CHECK(tool_calls BETWEEN 0 AND 8),
    cost_ceiling numeric NOT NULL CHECK(cost_ceiling>0 AND cost_ceiling<=1000000),
    reserved_cost numeric NOT NULL DEFAULT 0 CHECK(reserved_cost>=0 AND reserved_cost<=cost_ceiling),
    checkpoint_count integer NOT NULL DEFAULT 0 CHECK(checkpoint_count BETWEEN 0 AND 128),
    write_count integer NOT NULL DEFAULT 0 CHECK(write_count BETWEEN 0 AND 512),
    stored_bytes integer NOT NULL DEFAULT 0 CHECK(stored_bytes BETWEEN 0 AND 8388608),
    waiting_interrupt_id varchar(64),
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(id,graph_version),
    UNIQUE(invoice_case_id,context_hash,graph_version),
    FOREIGN KEY(parser_run_id,invoice_case_id,evidence_bundle_id)
        REFERENCES analysis_run(id,invoice_case_id,evidence_bundle_id),
    FOREIGN KEY(match_result_id,invoice_case_id,evidence_bundle_id)
        REFERENCES match_result(id,invoice_case_id,evidence_bundle_id),
    CHECK((status='RUNNING')=(execution_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK(status='RUNNING' OR (execution_token IS NULL AND lease_until IS NULL)),
    CHECK(active_segment<>'START' OR resume_attempts=0),
    CHECK(status<>'WAITING_HUMAN' OR (active_segment='START' AND waiting_interrupt_id IS NOT NULL))
);
CREATE INDEX ix_graph_run_case ON graph_run(invoice_case_id,created_at DESC,id);

CREATE TABLE graph_checkpoint (
    run_id uuid NOT NULL,
    checkpoint_id uuid NOT NULL,
    graph_version varchar(40) NOT NULL,
    parent_id uuid,
    envelope jsonb NOT NULL CHECK(jsonb_typeof(envelope)='object' AND octet_length(envelope::text)<=262144),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    execution_token uuid NOT NULL,
    sequence_number bigserial NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,checkpoint_id),
    UNIQUE(run_id,checkpoint_id,payload_hash),
    FOREIGN KEY(run_id,graph_version) REFERENCES graph_run(id,graph_version),
    FOREIGN KEY(run_id,parent_id) REFERENCES graph_checkpoint(run_id,checkpoint_id),
    CHECK(parent_id IS DISTINCT FROM checkpoint_id),
    CHECK(coalesce(envelope->>'threadId'=run_id::text AND envelope->>'checkpointId'=checkpoint_id::text
        AND envelope->>'graphVersion'=graph_version AND envelope->>'serializerVersion'='graph-checkpoint-json-v1'
        AND envelope->>'checkpointSchema'='2' AND envelope ? 'body' AND graph_json_allowed(envelope->'body',false)
        AND coalesce(envelope->>'parentId','')=coalesce(parent_id::text,'')
        AND jsonb_typeof(envelope->'metadata')='object' AND jsonb_typeof(envelope->'newVersions')='object',false))
);
CREATE TRIGGER trg_graph_checkpoint_immutable BEFORE UPDATE OR DELETE ON graph_checkpoint
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE graph_pending_write (
    run_id uuid NOT NULL,
    checkpoint_id uuid NOT NULL,
    task_id uuid NOT NULL,
    write_index integer NOT NULL CHECK(write_index BETWEEN -4 AND 511),
    version_number integer NOT NULL CHECK(version_number BETWEEN 1 AND 512),
    previous_version integer,
    previous_hash varchar(64),
    channel varchar(80) NOT NULL,
    task_path varchar(1) NOT NULL DEFAULT '' CHECK(task_path=''),
    payload jsonb NOT NULL CHECK(octet_length(payload::text)<=262144 AND graph_json_allowed(payload,true)),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    execution_token uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,checkpoint_id,task_id,write_index,version_number),
    UNIQUE(run_id,checkpoint_id,task_id,write_index,version_number,payload_hash),
    FOREIGN KEY(run_id,checkpoint_id) REFERENCES graph_checkpoint(run_id,checkpoint_id),
    FOREIGN KEY(run_id,checkpoint_id,task_id,write_index,previous_version,previous_hash)
        REFERENCES graph_pending_write(run_id,checkpoint_id,task_id,write_index,version_number,payload_hash),
    CHECK((version_number=1 AND previous_version IS NULL AND previous_hash IS NULL)
        OR (version_number>1 AND previous_version=version_number-1 AND previous_hash IS NOT NULL AND write_index<0))
);
CREATE TRIGGER trg_graph_write_immutable BEFORE UPDATE OR DELETE ON graph_pending_write
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE TABLE graph_interrupt (
    run_id uuid NOT NULL REFERENCES graph_run(id),
    interrupt_id varchar(64) NOT NULL CHECK(interrupt_id ~ '^[0-9a-f]{32,64}$'),
    checkpoint_id uuid NOT NULL,
    checkpoint_hash varchar(64) NOT NULL,
    task_id uuid NOT NULL,
    write_index integer NOT NULL CHECK(write_index=-3),
    write_version integer NOT NULL,
    write_hash varchar(64) NOT NULL,
    review_version integer NOT NULL DEFAULT 1 CHECK(review_version=1),
    execution_token uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,interrupt_id),
    UNIQUE(run_id),
    FOREIGN KEY(run_id,checkpoint_id,checkpoint_hash) REFERENCES graph_checkpoint(run_id,checkpoint_id,payload_hash),
    FOREIGN KEY(run_id,checkpoint_id,task_id,write_index,write_version,write_hash)
        REFERENCES graph_pending_write(run_id,checkpoint_id,task_id,write_index,version_number,payload_hash)
);
CREATE TRIGGER trg_graph_interrupt_immutable BEFORE UPDATE OR DELETE ON graph_interrupt
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
ALTER TABLE graph_run ADD CONSTRAINT fk_graph_waiting_interrupt FOREIGN KEY(id,waiting_interrupt_id)
    REFERENCES graph_interrupt(run_id,interrupt_id);

CREATE FUNCTION guard_graph_run() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR ROW(NEW.id,NEW.invoice_case_id,NEW.case_version,NEW.evidence_bundle_id,
        NEW.parser_run_id,NEW.match_result_id,NEW.context,NEW.context_hash,NEW.workflow_version,
        NEW.graph_version,NEW.serializer_version,NEW.checkpoint_schema,NEW.cost_ceiling,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.invoice_case_id,OLD.case_version,OLD.evidence_bundle_id,
        OLD.parser_run_id,OLD.match_result_id,OLD.context,OLD.context_hash,OLD.workflow_version,
        OLD.graph_version,OLD.serializer_version,OLD.checkpoint_schema,OLD.cost_ceiling,OLD.created_at) THEN
        RAISE EXCEPTION 'graph input immutable' USING ERRCODE='23000';
    END IF;
    IF OLD.status IN ('COMPLETED','FAILED','STALE') THEN
        RAISE EXCEPTION 'terminal graph' USING ERRCODE='23000';
    END IF;
    IF NEW.start_attempts<OLD.start_attempts OR NEW.resume_attempts<OLD.resume_attempts
        OR NEW.reserved_calls<OLD.reserved_calls OR NEW.reserved_tokens<OLD.reserved_tokens
        OR NEW.tool_calls<OLD.tool_calls OR NEW.reserved_cost<OLD.reserved_cost
        OR NEW.checkpoint_count<OLD.checkpoint_count OR NEW.write_count<OLD.write_count
        OR NEW.stored_bytes<OLD.stored_bytes THEN
        RAISE EXCEPTION 'graph counters cannot reset' USING ERRCODE='23514';
    END IF;
    IF NEW.status IN ('STALE','FAILED') THEN
        IF ROW(NEW.start_attempts,NEW.resume_attempts,NEW.active_segment,NEW.waiting_interrupt_id,
            NEW.reserved_calls,NEW.reserved_tokens,NEW.tool_calls,NEW.reserved_cost,
            NEW.checkpoint_count,NEW.write_count,NEW.stored_bytes)
            IS DISTINCT FROM ROW(OLD.start_attempts,OLD.resume_attempts,OLD.active_segment,OLD.waiting_interrupt_id,
            OLD.reserved_calls,OLD.reserved_tokens,OLD.tool_calls,OLD.reserved_cost,
            OLD.checkpoint_count,OLD.write_count,OLD.stored_bytes) THEN
            RAISE EXCEPTION 'termination cannot change counters' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status='RUNNING' AND (OLD.status='QUEUED' OR (OLD.status='RUNNING' AND OLD.lease_until<=clock_timestamp())) THEN
        IF NEW.execution_token IS NOT DISTINCT FROM OLD.execution_token OR NEW.lease_until<=clock_timestamp()
            OR NEW.active_segment<>OLD.active_segment OR NEW.waiting_interrupt_id IS DISTINCT FROM OLD.waiting_interrupt_id
            OR NEW.start_attempts<>OLD.start_attempts+(CASE WHEN OLD.active_segment='START' THEN 1 ELSE 0 END)
            OR NEW.resume_attempts<>OLD.resume_attempts+(CASE WHEN OLD.active_segment='RESUME' THEN 1 ELSE 0 END)
            OR ROW(NEW.reserved_calls,NEW.reserved_tokens,NEW.tool_calls,NEW.reserved_cost,
                NEW.checkpoint_count,NEW.write_count,NEW.stored_bytes)
            IS DISTINCT FROM ROW(OLD.reserved_calls,OLD.reserved_tokens,OLD.tool_calls,OLD.reserved_cost,
                OLD.checkpoint_count,OLD.write_count,OLD.stored_bytes) THEN
            RAISE EXCEPTION 'invalid graph claim' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.status='RUNNING' AND OLD.lease_until>clock_timestamp() THEN
        IF NEW.active_segment<>OLD.active_segment OR NEW.start_attempts<>OLD.start_attempts
            OR NEW.resume_attempts<>OLD.resume_attempts OR NEW.status NOT IN ('RUNNING','WAITING_HUMAN','QUEUED','COMPLETED')
            OR (NEW.status='RUNNING' AND (NEW.execution_token IS DISTINCT FROM OLD.execution_token OR NEW.lease_until<OLD.lease_until))
            OR (NEW.status<>'WAITING_HUMAN' AND NEW.waiting_interrupt_id IS DISTINCT FROM OLD.waiting_interrupt_id)
            OR (NEW.status='WAITING_HUMAN' AND (OLD.active_segment<>'START' OR OLD.waiting_interrupt_id IS NOT NULL)) THEN
            RAISE EXCEPTION 'invalid graph owner or transition' USING ERRCODE='23514';
        END IF;
        IF NEW.status<>'RUNNING' AND ROW(NEW.reserved_calls,NEW.reserved_tokens,NEW.tool_calls,NEW.reserved_cost,
            NEW.checkpoint_count,NEW.write_count,NEW.stored_bytes) IS DISTINCT FROM
            ROW(OLD.reserved_calls,OLD.reserved_tokens,OLD.tool_calls,OLD.reserved_cost,
            OLD.checkpoint_count,OLD.write_count,OLD.stored_bytes) THEN
            RAISE EXCEPTION 'settlement cannot change budget' USING ERRCODE='23514';
        END IF;
        IF NEW.status='WAITING_HUMAN' AND NOT EXISTS (
            SELECT 1 FROM graph_interrupt i JOIN graph_checkpoint c ON c.run_id=i.run_id AND c.checkpoint_id=i.checkpoint_id
                WHERE i.run_id=NEW.id AND i.interrupt_id=NEW.waiting_interrupt_id AND i.execution_token=OLD.execution_token
                AND c.sequence_number=(SELECT max(sequence_number) FROM graph_checkpoint WHERE run_id=NEW.id)
                AND i.write_version=(SELECT max(version_number) FROM graph_pending_write WHERE run_id=i.run_id
                    AND checkpoint_id=i.checkpoint_id AND task_id=i.task_id AND write_index=-3)
        ) THEN
            RAISE EXCEPTION 'waiting requires exact durable proof' USING ERRCODE='23514';
        END IF;
    ELSE
        -- WAITING -> QUEUED requires the immutable human review/resume ledger (P4-03).
        RAISE EXCEPTION 'inactive graph' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_run_guard BEFORE UPDATE OR DELETE ON graph_run
    FOR EACH ROW EXECUTE FUNCTION guard_graph_run();

CREATE FUNCTION guard_graph_initial_state() RETURNS trigger AS $$
BEGIN
    IF NEW.status<>'QUEUED' OR NEW.active_segment<>'START' OR NEW.execution_token IS NOT NULL OR NEW.lease_until IS NOT NULL
        OR NEW.waiting_interrupt_id IS NOT NULL OR NEW.error_code IS NOT NULL
        OR ROW(NEW.start_attempts,NEW.resume_attempts,NEW.reserved_calls,NEW.reserved_tokens,NEW.tool_calls,
            NEW.reserved_cost,NEW.checkpoint_count,NEW.write_count,NEW.stored_bytes) IS DISTINCT FROM ROW(0,0,0,0,0,0::numeric,0,0,0) THEN
        RAISE EXCEPTION 'invalid graph initial state' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_initial_state BEFORE INSERT ON graph_run
    FOR EACH ROW EXECUTE FUNCTION guard_graph_initial_state();

-- Serializes size/count admission with the run lock and fences every INSERT.
CREATE FUNCTION admit_graph_storage() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE; bytes integer;
BEGIN
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'missing graph' USING ERRCODE='23503'; END IF;
    IF r.status<>'RUNNING' OR r.execution_token<>NEW.execution_token OR r.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION 'inactive graph storage token' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='graph_checkpoint' THEN
        bytes=octet_length(NEW.envelope::text);
        UPDATE graph_run SET checkpoint_count=checkpoint_count+1,stored_bytes=stored_bytes+bytes WHERE id=r.id;
    ELSE
        bytes=octet_length(NEW.payload::text);
        UPDATE graph_run SET write_count=write_count+1,stored_bytes=stored_bytes+bytes WHERE id=r.id;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_checkpoint_admit BEFORE INSERT ON graph_checkpoint
    FOR EACH ROW EXECUTE FUNCTION admit_graph_storage();
CREATE TRIGGER trg_graph_write_admit BEFORE INSERT ON graph_pending_write
    FOR EACH ROW EXECUTE FUNCTION admit_graph_storage();

CREATE FUNCTION guard_graph_interrupt() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE; channel_name text;
BEGIN
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'missing graph' USING ERRCODE='23503'; END IF;
    IF r.status<>'RUNNING' OR r.active_segment<>'START' OR r.execution_token<>NEW.execution_token
        OR r.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION 'inactive graph interrupt' USING ERRCODE='23514';
    END IF;
    SELECT channel INTO channel_name FROM graph_pending_write WHERE run_id=NEW.run_id
        AND checkpoint_id=NEW.checkpoint_id AND task_id=NEW.task_id AND write_index=NEW.write_index
        AND version_number=NEW.write_version AND payload_hash=NEW.write_hash;
    IF channel_name IS NOT NULL AND channel_name<>'__interrupt__' THEN
        RAISE EXCEPTION 'invalid interrupt write' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_interrupt_guard BEFORE INSERT ON graph_interrupt
    FOR EACH ROW EXECUTE FUNCTION guard_graph_interrupt();
