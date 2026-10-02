-- P2-02 submitted document evidence and supplement reference preservation.
--
-- A submitted evidence bundle freezes the completed documents of the sealed
-- draft revision it belongs to. Those documents are referenced by the revision
-- they were received in, and a supplement revision inherits the prior
-- submission's references instead of copying files or documents. The reference
-- table is revision-scoped, immutable and only writable for the current open
-- draft, so a sealed revision can never retrospectively gain a document.
--
-- The canonical payload of a document-bearing bundle is distinguished from the
-- legacy, document-less payload by a persisted schema discriminator on
-- evidence_bundle. Existing bundles predate document evidence and are
-- backfilled as legacy; a newly inserted legacy bundle whose sealed revision
-- actually carries document references is rejected, so a document-bearing
-- submission can never be downgraded to a document-less bundle.

-- A reference proves its document belongs to the same case through this
-- composite candidate key on the immutable upload reservation.
ALTER TABLE document_upload
    ADD CONSTRAINT ux_document_upload_id_case UNIQUE (id, invoice_case_id);

CREATE TABLE draft_revision_document (
    draft_revision_id uuid NOT NULL,
    document_id uuid NOT NULL,
    invoice_case_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (draft_revision_id, document_id),
    CONSTRAINT fk_draft_revision_document_revision_case
        FOREIGN KEY (draft_revision_id, invoice_case_id) REFERENCES draft_revision (id, invoice_case_id),
    CONSTRAINT fk_draft_revision_document_document_case
        FOREIGN KEY (document_id, invoice_case_id) REFERENCES document_upload (id, invoice_case_id),
    CONSTRAINT fk_draft_revision_document_document
        FOREIGN KEY (document_id) REFERENCES document (id)
);

CREATE INDEX ix_draft_revision_document_document ON draft_revision_document (document_id);

-- A reference may only be written for the current open draft, mirroring the
-- document registration guard and the case -> revision lock order.
CREATE OR REPLACE FUNCTION guard_draft_revision_document_insert() RETURNS trigger AS $$
DECLARE
    case_id uuid;
    revision_id uuid;
    current_revision uuid;
    case_status text;
    revision_status text;
BEGIN
    case_id := NEW.invoice_case_id;
    revision_id := NEW.draft_revision_id;
    SELECT status, current_draft_revision_id INTO case_status, current_revision
      FROM invoice_case WHERE id = case_id FOR UPDATE;
    SELECT status INTO revision_status FROM draft_revision WHERE id = revision_id FOR UPDATE;
    IF case_status NOT IN ('DRAFT', 'SUPPLEMENT_REQUIRED') OR
       current_revision IS DISTINCT FROM revision_id OR revision_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'document reference requires the current open draft' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Backfill the original revision reference for every document registered under
-- the P2-01 baseline. This runs before the open-draft guard because those
-- documents were received in revisions that may already be sealed. Backfilling
-- never rewrites the legacy bundle of a sealed revision.
INSERT INTO draft_revision_document (draft_revision_id, document_id, invoice_case_id, created_at)
SELECT u.draft_revision_id, d.id, u.invoice_case_id, d.registered_at
  FROM document d
  JOIN document_upload u ON u.id = d.id;

CREATE TRIGGER trg_draft_revision_document_open
    BEFORE INSERT ON draft_revision_document
    FOR EACH ROW EXECUTE FUNCTION guard_draft_revision_document_insert();

CREATE TRIGGER trg_draft_revision_document_immutable
    BEFORE UPDATE OR DELETE ON draft_revision_document
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Persisted canonical schema of a frozen evidence bundle. The default keeps
-- existing and raw legacy inserts working; the application always states the
-- schema explicitly for new submissions.
ALTER TABLE evidence_bundle
    ADD COLUMN payload_schema varchar(16) NOT NULL DEFAULT 'legacy-v1';

ALTER TABLE evidence_bundle
    ADD CONSTRAINT ck_evidence_bundle_payload_schema
    CHECK (payload_schema IN ('legacy-v1', 'document-v2'));

-- A legacy payload may only freeze a revision with no document references. A
-- document-bearing sealed revision must be frozen with the document-v2 schema,
-- which makes a forged legacy downgrade impossible at the database boundary.
CREATE OR REPLACE FUNCTION guard_evidence_bundle_payload_schema() RETURNS trigger AS $$
BEGIN
    IF NEW.payload_schema = 'legacy-v1'
       AND EXISTS (SELECT 1 FROM draft_revision_document r
                   WHERE r.draft_revision_id = NEW.draft_revision_id) THEN
        RAISE EXCEPTION 'a document-bearing sealed revision requires a document-v2 evidence bundle'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_evidence_bundle_payload_schema
    BEFORE INSERT ON evidence_bundle
    FOR EACH ROW EXECUTE FUNCTION guard_evidence_bundle_payload_schema();
