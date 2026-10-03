CREATE TABLE document_upload_cleanup (
    upload_id uuid PRIMARY KEY REFERENCES document_upload(id),
    status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','CLAIMED','DONE','BLOCKED')),
    claim_token uuid,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    last_error_code varchar(64) CHECK(last_error_code IN ('DOCUMENT_STORAGE_UNAVAILABLE','INVALID_UPLOAD_KEY')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((status='CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
       OR (status<>'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL)),
    CHECK((status='DONE')=(completed_at IS NOT NULL)),
    CHECK(status<>'BLOCKED' OR last_error_code IS NOT DISTINCT FROM 'INVALID_UPLOAD_KEY'),
    CHECK(status='BLOCKED' OR last_error_code IS DISTINCT FROM 'INVALID_UPLOAD_KEY'),
    CHECK(status<>'DONE' OR last_error_code IS NULL)
);
CREATE INDEX ix_document_upload_expiry ON document_upload(expires_at,id);
CREATE INDEX ix_upload_cleanup_due ON document_upload_cleanup(next_attempt_at,upload_id) WHERE status='READY';
CREATE INDEX ix_upload_cleanup_lease ON document_upload_cleanup(lease_until) WHERE status='CLAIMED';
CREATE FUNCTION guard_upload_cleanup() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'cleanup history cannot be deleted' USING ERRCODE='23000'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'READY' OR NEW.attempt_count<>0 OR NEW.last_error_code IS NOT NULL THEN
            RAISE EXCEPTION 'cleanup starts ready' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.upload_id IS DISTINCT FROM OLD.upload_id OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'cleanup identity is immutable' USING ERRCODE='23000';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    IF OLD.status='READY' AND NEW.status='CLAIMED' THEN
        IF NEW.attempt_count<>OLD.attempt_count+1 OR NEW.lease_until<=clock_timestamp()
           OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code
           OR NOT EXISTS(select 1 from document_upload where id=NEW.upload_id and expires_at+interval '1 hour'<=clock_timestamp()) THEN
            RAISE EXCEPTION 'invalid cleanup claim' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.status='CLAIMED' AND NEW.status IN ('READY','DONE','BLOCKED') THEN
        IF NEW.attempt_count<>OLD.attempt_count OR (NEW.status IN ('DONE','BLOCKED') AND OLD.lease_until<=clock_timestamp()) THEN
            RAISE EXCEPTION 'invalid cleanup settlement' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid cleanup transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_upload_cleanup_guard BEFORE INSERT OR UPDATE OR DELETE ON document_upload_cleanup
    FOR EACH ROW EXECUTE FUNCTION guard_upload_cleanup();
