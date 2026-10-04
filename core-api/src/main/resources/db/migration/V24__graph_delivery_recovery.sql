-- Immutable intent/proof, mutable delivery leases. Human opinions never travel over RabbitMQ.
ALTER TABLE graph_run ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE TABLE graph_event (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES graph_run(id),
    segment varchar(6) NOT NULL CHECK(segment IN ('START','RESUME')),
    payload jsonb NOT NULL CHECK(octet_length(payload::text)<=4096),
    UNIQUE(run_id,segment), UNIQUE(id,run_id)
);
CREATE TABLE graph_dispatch (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES graph_event(id),
    dedup_key varchar(100) NOT NULL,
    status varchar(9) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','CLAIMED','PUBLISHED','CANCELLED')),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    dispatch_token uuid, lease_until timestamptz, published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    UNIQUE(event_id,dedup_key),
    CHECK((status='CLAIMED')=(dispatch_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_graph_dispatch_ready ON graph_dispatch(next_attempt_at) WHERE status IN ('READY','CLAIMED');
CREATE TABLE graph_resume_consumption (
    event_id uuid PRIMARY KEY REFERENCES graph_resume_outbox(id),
    run_id uuid NOT NULL UNIQUE REFERENCES graph_run(id),
    review_id uuid NOT NULL UNIQUE REFERENCES graph_review(id),
    first_token uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(event_id,review_id) REFERENCES graph_resume_outbox(id,review_id)
);
CREATE TABLE graph_failure (
    run_id uuid NOT NULL REFERENCES graph_run(id), execution_token uuid NOT NULL,
    segment varchar(6) NOT NULL CHECK(segment IN ('START','RESUME')),
    error_code varchar(40) NOT NULL,
    run_status varchar(6) NOT NULL CHECK(run_status IN ('QUEUED','FAILED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(run_id,execution_token)
);
CREATE TRIGGER trg_graph_event_immutable BEFORE UPDATE OR DELETE ON graph_event FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER trg_graph_consumption_immutable BEFORE UPDATE OR DELETE ON graph_resume_consumption FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER trg_graph_failure_immutable BEFORE UPDATE OR DELETE ON graph_failure FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE FUNCTION guard_graph_event() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE;
BEGIN
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND OR (NEW.segment='START' AND (NEW.id<>r.id OR NEW.payload<>
        jsonb_build_object('schemaVersion','graph-request-v1','eventId',r.id::text,'graphExecutionId',r.id::text,
            'workflowVersion',r.workflow_version,'contextHash',r.context_hash)))
        OR (NEW.segment='RESUME' AND NOT EXISTS(SELECT 1 FROM graph_resume_outbox e
            WHERE e.id=NEW.id AND e.run_id=NEW.run_id AND e.payload=NEW.payload)) THEN
        RAISE EXCEPTION 'graph event identity mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_event_guard BEFORE INSERT ON graph_event FOR EACH ROW EXECUTE FUNCTION guard_graph_event();
CREATE FUNCTION dispatch_graph_resume() RETURNS trigger AS $$
BEGIN
    INSERT INTO graph_event(id,run_id,segment,payload) VALUES(NEW.id,NEW.run_id,'RESUME',NEW.payload);
    INSERT INTO graph_dispatch(id,event_id,dedup_key) VALUES(NEW.id,NEW.id,'initial');
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dispatch_graph_resume AFTER INSERT ON graph_resume_outbox FOR EACH ROW EXECUTE FUNCTION dispatch_graph_resume();
-- Existing durable reservations are eligible; waiting/terminal events are cancelled by relay policy.
INSERT INTO graph_event(id,run_id,segment,payload)
    SELECT id,id,'START',jsonb_build_object('schemaVersion','graph-request-v1','eventId',id::text,
        'graphExecutionId',id::text,'workflowVersion',workflow_version,'contextHash',context_hash)
    FROM graph_run WHERE checkpoint_schema=4;
INSERT INTO graph_event(id,run_id,segment,payload) SELECT id,run_id,'RESUME',payload FROM graph_resume_outbox;
INSERT INTO graph_dispatch(id,event_id,dedup_key) SELECT id,id,'initial' FROM graph_event;
CREATE FUNCTION guard_graph_consumption() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE;
BEGIN
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND OR r.status<>'RUNNING' OR r.active_segment<>'RESUME'
        OR r.execution_token<>NEW.first_token OR r.lease_until<=clock_timestamp()
        OR NOT EXISTS(SELECT 1 FROM graph_resume_outbox e WHERE e.id=NEW.event_id AND e.run_id=r.id
            AND e.review_id=NEW.review_id AND e.interrupt_id=r.waiting_interrupt_id) THEN
        RAISE EXCEPTION 'resume requires exact active identity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_consumption_guard BEFORE INSERT ON graph_resume_consumption FOR EACH ROW EXECUTE FUNCTION guard_graph_consumption();
CREATE FUNCTION guard_graph_failure() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE;
BEGIN
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND OR r.status<>'RUNNING' OR r.active_segment<>NEW.segment
        OR r.execution_token<>NEW.execution_token OR r.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION 'failure requires active fenced owner' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_failure_guard BEFORE INSERT ON graph_failure FOR EACH ROW EXECUTE FUNCTION guard_graph_failure();
