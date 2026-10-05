-- A changed shared input invalidates an old terminal graph result without rewriting its content.
-- Only the status transition to STALE is admitted; counters, segment, wait, token and content stay frozen.
CREATE OR REPLACE FUNCTION guard_graph_run() RETURNS trigger AS $$
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
        IF NEW.status='STALE' AND NEW.execution_token IS NULL AND NEW.lease_until IS NULL AND NEW.error_code IS NULL
            AND ROW(NEW.start_attempts,NEW.resume_attempts,NEW.active_segment,NEW.waiting_interrupt_id,
                NEW.reserved_calls,NEW.reserved_tokens,NEW.tool_calls,NEW.reserved_cost,
                NEW.checkpoint_count,NEW.write_count,NEW.stored_bytes)
            IS NOT DISTINCT FROM ROW(OLD.start_attempts,OLD.resume_attempts,OLD.active_segment,OLD.waiting_interrupt_id,
                OLD.reserved_calls,OLD.reserved_tokens,OLD.tool_calls,OLD.reserved_cost,
                OLD.checkpoint_count,OLD.write_count,OLD.stored_bytes) THEN
            RETURN NEW;
        END IF;
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
    ELSIF OLD.status='WAITING_HUMAN' AND NEW.status='QUEUED' THEN
        IF OLD.active_segment<>'START' OR NEW.active_segment<>'RESUME'
            OR NEW.waiting_interrupt_id IS DISTINCT FROM OLD.waiting_interrupt_id
            OR ROW(NEW.start_attempts,NEW.resume_attempts,NEW.reserved_calls,NEW.reserved_tokens,
                NEW.tool_calls,NEW.reserved_cost,NEW.checkpoint_count,NEW.write_count,NEW.stored_bytes)
            IS DISTINCT FROM ROW(OLD.start_attempts,OLD.resume_attempts,OLD.reserved_calls,OLD.reserved_tokens,
                OLD.tool_calls,OLD.reserved_cost,OLD.checkpoint_count,OLD.write_count,OLD.stored_bytes)
            OR NOT EXISTS(SELECT 1 FROM graph_review h JOIN graph_resume_outbox e ON e.id=h.resume_event_id
                AND e.review_id=h.id WHERE h.run_id=NEW.id AND h.interrupt_id=OLD.waiting_interrupt_id) THEN
            RAISE EXCEPTION 'resume requires exact immutable review and outbox without budget reset' USING ERRCODE='23514';
        END IF;
    ELSE
        -- Inactive executions cannot alter their state without the durable resume ledger.
        RAISE EXCEPTION 'inactive graph' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
