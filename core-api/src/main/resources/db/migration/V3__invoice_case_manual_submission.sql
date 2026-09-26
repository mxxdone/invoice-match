-- P1-03 manual invoice submission and request-id idempotency.
--
-- The draft, evidence bundle, version and hash columns already exist from the
-- P1-01 baseline. This migration adds the two things submission needs at the
-- database level:
--
-- 1. An idempotency record keyed by (operation scope, aggregate/resource,
--    request id). A unique constraint makes concurrent identical requests race
--    safely: the loser observes the stored response and replays it instead of
--    repeating the side effect. A different canonical payload for the same key
--    is a conflict.
-- 2. A trigger that makes the invoice lines of a sealed draft revision
--    immutable, so no code path can quietly edit or delete the lines behind an
--    already frozen EvidenceBundle version.

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY,
    scope varchar(64) NOT NULL,
    resource_key varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    request_hash varchar(128) NOT NULL,
    response_status integer,
    response_body text,
    created_at timestamptz NOT NULL,
    CONSTRAINT ux_idempotency_request UNIQUE (scope, resource_key, request_id)
);

-- A draft revision is only editable while OPEN. Once it is SEALED its invoice
-- lines are part of an immutable EvidenceBundle version and must never change.
-- Application code already only edits the current OPEN revision; this trigger
-- enforces the invariant at the DB for INSERT, UPDATE and DELETE.
CREATE OR REPLACE FUNCTION reject_sealed_revision_line_change() RETURNS trigger AS $$
DECLARE
    target_revision uuid;
BEGIN
    target_revision := COALESCE(NEW.draft_revision_id, OLD.draft_revision_id);
    IF EXISTS (SELECT 1 FROM draft_revision dr WHERE dr.id = target_revision AND dr.status = 'SEALED') THEN
        RAISE EXCEPTION 'invoice_line rows of a sealed draft revision are immutable'
            USING ERRCODE = 'raise_exception';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_line_sealed_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON invoice_line
    FOR EACH ROW EXECUTE FUNCTION reject_sealed_revision_line_change();
