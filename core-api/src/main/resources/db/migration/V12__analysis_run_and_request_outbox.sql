-- P2-05 AnalysisRun reservation and analysis-request transactional Outbox.
--
-- A submission freezes an immutable evidence bundle. When analysis is enabled
-- and the bundle actually froze documents, the same submission transaction also
-- reserves one AnalysisRun and one analysis-request Outbox row, so the
-- committed business state and the to-be-published request are never separated.
-- A newer bundle submission STALEs every lower input_version run of the same
-- case and CANCELLEs its READY request, so a stale request can never be
-- published after a correction. This migration is the reservation side only;
-- the RabbitMQ relay, Python consumer and result reflection are P2-06.
--
-- State in P2-05 is deliberately minimal:
--   analysis_run.status            QUEUED  -> STALE
--   analysis_request_outbox.status READY   -> CANCELLED
-- Run input identity/hash/workflow and Outbox schema/payload are append-only;
-- DELETE is rejected for both tables.
--
-- The composite foreign-key target already exists from V1:
--   evidence_bundle (id, invoice_case_id, version_number).

CREATE TABLE analysis_run (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL,
    evidence_bundle_id uuid NOT NULL,
    input_version integer NOT NULL,
    evidence_payload_hash varchar(128) NOT NULL,
    workflow_version varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_analysis_run_status CHECK (status IN ('QUEUED', 'STALE')),
    CONSTRAINT ck_analysis_run_workflow_version CHECK (workflow_version = 'document-parser-v1'),
    CONSTRAINT ck_analysis_run_input_version CHECK (input_version > 0),
    -- One run per (case, evidence version, workflow contract).
    CONSTRAINT ux_analysis_run_case_version_workflow
        UNIQUE (invoice_case_id, input_version, workflow_version),
    -- The frozen bundle/version must belong to the same case.
    CONSTRAINT fk_analysis_run_bundle_same_case_version
        FOREIGN KEY (evidence_bundle_id, invoice_case_id, input_version)
        REFERENCES evidence_bundle (id, invoice_case_id, version_number)
);

CREATE INDEX ix_analysis_run_case_status
    ON analysis_run (invoice_case_id, status, input_version);

CREATE TABLE analysis_request_outbox (
    id uuid PRIMARY KEY,
    analysis_run_id uuid NOT NULL,
    schema_version varchar(32) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_analysis_request_outbox_schema CHECK (schema_version = 'analysis-request-v1'),
    CONSTRAINT ck_analysis_request_outbox_status CHECK (status IN ('READY', 'CANCELLED')),
    -- Exactly one request per run; the relay contract in P2-06.
    CONSTRAINT ux_analysis_request_outbox_run UNIQUE (analysis_run_id),
    CONSTRAINT fk_analysis_request_outbox_run
        FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id)
);

-- Run inputs (identity, frozen hash, workflow) are immutable after creation and
-- the row is not deletable; only QUEUED -> STALE advances.
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
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (OLD.status = 'QUEUED' AND NEW.status = 'STALE') THEN
            RAISE EXCEPTION 'illegal analysis_run status transition % -> %', OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_analysis_run_mutation
    BEFORE UPDATE OR DELETE ON analysis_run
    FOR EACH ROW EXECUTE FUNCTION guard_analysis_run_mutation();

-- Outbox subject, schema and payload are immutable; the row is not deletable;
-- only READY -> CANCELLED advances. P2-06 adds the lease/send fields and relay.
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
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (OLD.status = 'READY' AND NEW.status = 'CANCELLED') THEN
            RAISE EXCEPTION 'illegal analysis_request_outbox status transition % -> %',
                OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_analysis_request_outbox_mutation
    BEFORE UPDATE OR DELETE ON analysis_request_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_analysis_request_outbox_mutation();
