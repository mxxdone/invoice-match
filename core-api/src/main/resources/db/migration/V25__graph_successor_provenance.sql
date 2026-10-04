-- Provenance is append-only and never copies stages, reviews or budgets.
ALTER TABLE graph_run ADD CONSTRAINT ux_graph_run_case UNIQUE(id,invoice_case_id);
CREATE TABLE graph_successor (
    run_id uuid PRIMARY KEY,
    predecessor_id uuid NOT NULL,
    invoice_case_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(run_id,invoice_case_id) REFERENCES graph_run(id,invoice_case_id),
    FOREIGN KEY(predecessor_id,invoice_case_id) REFERENCES graph_run(id,invoice_case_id),
    CHECK(run_id<>predecessor_id)
);
CREATE TRIGGER trg_graph_successor_immutable BEFORE UPDATE OR DELETE ON graph_successor
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE FUNCTION guard_graph_successor() RETURNS trigger AS $$
DECLARE r graph_run%ROWTYPE; p graph_run%ROWTYPE;
BEGIN
    SELECT * INTO p FROM graph_run WHERE id=NEW.predecessor_id FOR UPDATE;
    SELECT * INTO r FROM graph_run WHERE id=NEW.run_id FOR UPDATE;
    IF p.status<>'STALE' OR p.context_hash=r.context_hash OR r.status<>'QUEUED'
        OR r.active_segment<>'START' OR r.start_attempts<>0 OR r.resume_attempts<>0
        OR r.reserved_calls<>0 OR r.reserved_tokens<>0 OR r.tool_calls<>0 OR r.reserved_cost<>0
        OR r.checkpoint_count<>0 OR r.write_count<>0 OR r.waiting_interrupt_id IS NOT NULL THEN
        RAISE EXCEPTION 'successor requires changed input and a fresh execution' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_graph_successor_guard BEFORE INSERT ON graph_successor
    FOR EACH ROW EXECUTE FUNCTION guard_graph_successor();
