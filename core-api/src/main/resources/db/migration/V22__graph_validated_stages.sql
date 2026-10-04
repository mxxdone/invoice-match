-- SDK checkpoints contain references; authoritative stage payloads stay outside SDK state.
-- The pinned runtime writes schema 4; checkpoint-base's legacy constant is 2.
-- Preserve schema 2 records, reject automatic loading and admit only schema 4 new runs.
ALTER TABLE graph_run DROP CONSTRAINT graph_run_checkpoint_schema_check;
ALTER TABLE graph_run ADD CONSTRAINT graph_run_checkpoint_schema_check CHECK(checkpoint_schema IN (2,4));
CREATE FUNCTION guard_graph_writer_schema() RETURNS trigger AS $$
BEGIN
    IF NEW.checkpoint_schema<>4 THEN RAISE EXCEPTION 'unsupported graph writer schema' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_writer_schema BEFORE INSERT ON graph_run
    FOR EACH ROW EXECUTE FUNCTION guard_graph_writer_schema();
ALTER TABLE graph_run ADD CONSTRAINT ux_graph_schema UNIQUE(id,graph_version,checkpoint_schema);
DO $$ DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint WHERE conrelid='graph_checkpoint'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%checkpointSchema%' LOOP
        EXECUTE format('ALTER TABLE graph_checkpoint DROP CONSTRAINT %I',constraint_name);
    END LOOP;
END $$;
ALTER TABLE graph_checkpoint ADD COLUMN checkpoint_schema integer GENERATED ALWAYS AS ((envelope->>'checkpointSchema')::integer) STORED;
ALTER TABLE graph_checkpoint ADD CONSTRAINT fk_graph_checkpoint_schema FOREIGN KEY(run_id,graph_version,checkpoint_schema)
    REFERENCES graph_run(id,graph_version,checkpoint_schema);
ALTER TABLE graph_checkpoint ADD CONSTRAINT graph_checkpoint_sdk_envelope_check CHECK(coalesce(
    envelope->>'threadId'=run_id::text AND envelope->>'checkpointId'=checkpoint_id::text
    AND envelope->>'graphVersion'=graph_version AND envelope->>'serializerVersion'='graph-checkpoint-json-v1'
    AND checkpoint_schema IN (2,4) AND graph_json_allowed(envelope->'body',false)
    AND (checkpoint_schema=2 OR envelope->'body'->1->'v'->1=to_jsonb(checkpoint_schema))
    AND coalesce(envelope->>'parentId','')=coalesce(parent_id::text,'')
    AND jsonb_typeof(envelope->'metadata')='object' AND jsonb_typeof(envelope->'newVersions')='object',false));
ALTER TABLE graph_pending_write DROP CONSTRAINT graph_pending_write_task_path_check;
ALTER TABLE graph_pending_write ALTER COLUMN task_path TYPE varchar(80);
ALTER TABLE graph_pending_write ADD CONSTRAINT graph_pending_write_task_path_check CHECK(
    task_path='' OR task_path IN ('~__pregel_pull, __start__','~__pregel_pull, execution','~__pregel_pull, document',
        '~__pregel_pull, mapping','~__pregel_pull, human','~__pregel_pull, evidence','~__pregel_pull, resolution'));
CREATE TABLE graph_stage (
    id uuid NOT NULL UNIQUE,
    run_id uuid NOT NULL REFERENCES graph_run(id),
    stage varchar(80) NOT NULL,
    payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=131072),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    execution_token uuid NOT NULL,
    PRIMARY KEY(run_id,stage)
);
CREATE TRIGGER trg_graph_stage_immutable BEFORE UPDATE OR DELETE ON graph_stage
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TABLE graph_call_reservation (
    run_id uuid NOT NULL REFERENCES graph_run(id),
    request_id uuid NOT NULL,
    reserved_tokens integer NOT NULL CHECK(reserved_tokens BETWEEN 1 AND 40000),
    reserved_cost numeric NOT NULL CHECK(reserved_cost>=0),
    execution_token uuid NOT NULL,
    PRIMARY KEY(run_id,request_id)
);
CREATE TRIGGER trg_graph_call_immutable BEFORE UPDATE OR DELETE ON graph_call_reservation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TABLE graph_proposal (
    run_id uuid PRIMARY KEY REFERENCES graph_run(id),
    payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=131072),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    execution_token uuid NOT NULL
);
CREATE TRIGGER trg_graph_proposal_immutable BEFORE UPDATE OR DELETE ON graph_proposal
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE FUNCTION admit_graph_stage() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE;
BEGIN
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'missing graph' USING ERRCODE='23503'; END IF;
    IF r.status<>'RUNNING' OR r.execution_token<>NEW.execution_token OR r.lease_until<=clock_timestamp() THEN
        RAISE EXCEPTION 'inactive graph stage token' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='graph_call_reservation' THEN
        UPDATE graph_run SET reserved_calls=reserved_calls+1,reserved_tokens=reserved_tokens+NEW.reserved_tokens,
            reserved_cost=reserved_cost+NEW.reserved_cost WHERE id=r.id;
    ELSE
        IF TG_TABLE_NAME='graph_stage' THEN
            IF (SELECT count(*) FROM graph_stage WHERE run_id=r.id)>=32 THEN
                RAISE EXCEPTION 'graph stage limit' USING ERRCODE='23514';
            END IF;
            IF NEW.stage LIKE 'tool:%' THEN
                UPDATE graph_run SET tool_calls=tool_calls+1 WHERE id=r.id;
            END IF;
        END IF;
        UPDATE graph_run SET stored_bytes=stored_bytes+octet_length(NEW.payload::text) WHERE id=r.id;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_stage_admit BEFORE INSERT ON graph_stage
    FOR EACH ROW EXECUTE FUNCTION admit_graph_stage();
CREATE TRIGGER trg_graph_call_admit BEFORE INSERT ON graph_call_reservation
    FOR EACH ROW EXECUTE FUNCTION admit_graph_stage();
CREATE TRIGGER trg_graph_proposal_admit BEFORE INSERT ON graph_proposal
    FOR EACH ROW EXECUTE FUNCTION admit_graph_stage();
