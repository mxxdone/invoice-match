CREATE TABLE document_upload (
    id uuid PRIMARY KEY,
    invoice_case_id uuid NOT NULL REFERENCES invoice_case(id),
    draft_revision_id uuid NOT NULL,
    file_name varchar(255) NOT NULL,
    media_type varchar(100) NOT NULL CHECK (media_type IN (
        'application/pdf', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    checksum varchar(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    upload_key varchar(255) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (draft_revision_id, invoice_case_id) REFERENCES draft_revision(id, invoice_case_id)
);
CREATE INDEX ix_document_upload_revision ON document_upload(draft_revision_id);

CREATE TABLE document (
    id uuid PRIMARY KEY REFERENCES document_upload(id),
    object_key varchar(255) NOT NULL UNIQUE,
    registered_case_version bigint NOT NULL CHECK (registered_case_version >= 0),
    registered_at timestamptz NOT NULL
);
CREATE TRIGGER trg_document_immutable BEFORE UPDATE OR DELETE ON document
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER trg_document_upload_immutable BEFORE UPDATE OR DELETE ON document_upload
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Raw SQL also observes the case -> revision lock order and open-draft rule.
CREATE FUNCTION guard_document_insert() RETURNS trigger AS $$
DECLARE
    case_id uuid;
    revision_id uuid;
    current_revision uuid;
    case_status text;
    revision_status text;
BEGIN
    IF TG_TABLE_NAME = 'document_upload' THEN
        case_id := NEW.invoice_case_id;
        revision_id := NEW.draft_revision_id;
    ELSE
        SELECT invoice_case_id, draft_revision_id INTO case_id, revision_id
          FROM document_upload WHERE id = NEW.id;
    END IF;
    SELECT status, current_draft_revision_id INTO case_status, current_revision
      FROM invoice_case WHERE id = case_id FOR UPDATE;
    SELECT status INTO revision_status FROM draft_revision WHERE id = revision_id FOR UPDATE;
    IF case_status NOT IN ('DRAFT', 'SUPPLEMENT_REQUIRED') OR
       current_revision IS DISTINCT FROM revision_id OR revision_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'document requires the current open draft' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_upload_open BEFORE INSERT ON document_upload
    FOR EACH ROW EXECUTE FUNCTION guard_document_insert();
CREATE TRIGGER trg_document_open BEFORE INSERT ON document
    FOR EACH ROW EXECUTE FUNCTION guard_document_insert();

ALTER TABLE audit_entry DROP CONSTRAINT ck_audit_entry_action;
ALTER TABLE audit_entry ADD CONSTRAINT ck_audit_entry_action CHECK (action IN (
    'CASE_CREATED', 'DRAFT_LINES_REPLACED', 'CASE_SUBMITTED', 'SUPPLEMENT_REVISION_OPENED',
    'MATCH_RUN', 'REVIEW_SNAPSHOT_FROZEN', 'ITEM_MAPPED', 'SUPPLEMENT_REQUESTED',
    'CASE_REJECTED', 'APPROVE', 'DOCUMENT_UPLOAD_RESERVED', 'DOCUMENT_REGISTERED'));
