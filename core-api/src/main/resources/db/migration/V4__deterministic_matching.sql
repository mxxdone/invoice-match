-- P1-04 deterministic 3-way matching.
--
-- match_result already exists from the P1-01 baseline, is append-only through
-- the reject_immutable_change trigger and is tied to a case and evidence bundle
-- by the existing composite foreign key. Matching adds:
--
-- 1. A NOT NULL evidence_bundle_id: every deterministic result is produced from
--    a specific frozen bundle, so a bundleness result is meaningless and is
--    rejected at the database as well as in the entity factory.
-- 2. A per-case monotonic result_number. created_at plus a random UUID does not
--    define append order for equal timestamps, so latest/history rely on this
--    number instead. Matching serializes on the invoice case row lock, so
--    allocating max+1 is safe; the unique constraint is the final guard.
-- 3. An index supporting the per-case history and latest-result reads.

ALTER TABLE match_result
    ADD COLUMN result_number integer;

ALTER TABLE match_result
    ALTER COLUMN result_number SET NOT NULL,
    ALTER COLUMN evidence_bundle_id SET NOT NULL;

ALTER TABLE match_result
    ADD CONSTRAINT ck_match_result_result_number CHECK (result_number > 0),
    ADD CONSTRAINT ux_match_result_case_result_number UNIQUE (invoice_case_id, result_number);

CREATE INDEX ix_match_result_case_result_number
    ON match_result (invoice_case_id, result_number);
