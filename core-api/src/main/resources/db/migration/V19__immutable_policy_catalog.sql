-- Optional exact-vector search; ordinary PostgreSQL remains a valid AI-off host.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name='vector') THEN
        CREATE EXTENSION IF NOT EXISTS vector;
    END IF;
END $$;

CREATE TABLE policy_contract (
    company_id varchar(64) NOT NULL,
    supplier_id varchar(64) NOT NULL,
    contract_id varchar(64) NOT NULL,
    PRIMARY KEY(company_id,supplier_id,contract_id)
);
CREATE TABLE policy_contract_purchase_order (
    company_id varchar(64) NOT NULL,
    supplier_id varchar(64) NOT NULL,
    contract_id varchar(64) NOT NULL,
    purchase_order_id varchar(64) NOT NULL REFERENCES purchase_order_snapshot(purchase_order_id),
    PRIMARY KEY(company_id,supplier_id,contract_id,purchase_order_id),
    FOREIGN KEY(company_id,supplier_id,contract_id) REFERENCES policy_contract
);
CREATE TABLE policy_document (
    id uuid PRIMARY KEY,
    company_id varchar(64) NOT NULL,
    supplier_id varchar(64) NOT NULL,
    contract_id varchar(64) NOT NULL,
    document_key varchar(64) NOT NULL,
    version integer NOT NULL CHECK(version>0),
    title varchar(200) NOT NULL,
    valid_from date NOT NULL,
    valid_to date NOT NULL CHECK(valid_to>=valid_from),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    embedding_model varchar(100) NOT NULL,
    embedding_version varchar(100) NOT NULL,
    embedding_dimension integer NOT NULL CHECK(embedding_dimension BETWEEN 1 AND 3072),
    read_scope varchar(32) NOT NULL CHECK(read_scope IN ('CASE_REVIEW','RESTRICTED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(company_id,supplier_id,contract_id,document_key,version),
    FOREIGN KEY(company_id,supplier_id,contract_id) REFERENCES policy_contract
);
CREATE TABLE policy_chunk (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES policy_document(id),
    page integer NOT NULL CHECK(page BETWEEN 1 AND 1000),
    paragraph integer NOT NULL CHECK(paragraph BETWEEN 1 AND 1000),
    content varchar(2000) NOT NULL CHECK(length(btrim(content))>0),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    embedding real[] NOT NULL CHECK(cardinality(embedding) BETWEEN 1 AND 3072 AND array_ndims(embedding)=1),
    rule_key varchar(64) CHECK(rule_key ~ '^[A-Z0-9_]{1,64}$'),
    effect varchar(16) NOT NULL CHECK(effect IN ('INFORMATION','ALLOW','DENY')),
    CHECK(effect='INFORMATION' OR rule_key IS NOT NULL),
    UNIQUE(document_id,page,paragraph)
);
CREATE INDEX ix_policy_scope ON policy_document(company_id,supplier_id,contract_id,document_key,version DESC);
CREATE INDEX ix_policy_chunk_document ON policy_chunk(document_id,page,paragraph);
CREATE TRIGGER policy_contract_immutable BEFORE UPDATE OR DELETE ON policy_contract FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER policy_link_immutable BEFORE UPDATE OR DELETE ON policy_contract_purchase_order FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER policy_document_immutable BEFORE UPDATE OR DELETE ON policy_document FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER policy_chunk_immutable BEFORE UPDATE OR DELETE ON policy_chunk FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

CREATE OR REPLACE FUNCTION guard_policy_chunk_insert() RETURNS trigger AS $$
DECLARE expected_dimension integer;
BEGIN
    SELECT embedding_dimension INTO expected_dimension FROM policy_document WHERE id=NEW.document_id;
    IF expected_dimension IS NOT NULL AND (
        cardinality(NEW.embedding)<>expected_dimension OR array_lower(NEW.embedding,1)<>1
        OR EXISTS(SELECT 1 FROM unnest(NEW.embedding) e WHERE e IS NULL OR NOT(e>'-Infinity'::real AND e<'Infinity'::real) OR abs(e)>1000000)
        OR NOT (SELECT sum(e::double precision*e::double precision) BETWEEN 1e-12 AND 1e12 FROM unnest(NEW.embedding) e)
    ) THEN RAISE EXCEPTION 'Invalid policy embedding' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER policy_chunk_insert_guard BEFORE INSERT ON policy_chunk FOR EACH ROW EXECUTE FUNCTION guard_policy_chunk_insert();

ALTER TABLE audit_entry DROP CONSTRAINT ck_audit_entry_action;
ALTER TABLE audit_entry ADD CONSTRAINT ck_audit_entry_action CHECK(action IN (
    'CASE_CREATED','DRAFT_LINES_REPLACED','CASE_SUBMITTED','SUPPLEMENT_REVISION_OPENED',
    'MATCH_RUN','REVIEW_SNAPSHOT_FROZEN','ITEM_MAPPED','SUPPLEMENT_REQUESTED','CASE_REJECTED','APPROVE',
    'DOCUMENT_UPLOAD_RESERVED','DOCUMENT_REGISTERED','ANALYSIS_RETRY_RESERVED','AI_ANALYSIS_RESERVED','POLICY_DOCUMENT_PUBLISHED'));
