-- P1-04 deterministic 3-way matching.
--
-- match_result already exists from the P1-01 baseline, is append-only through
-- the reject_immutable_change trigger and is tied to a case and evidence bundle
-- by the existing composite foreign key. Matching only needs a supporting index
-- for the append-only per-case history and the "latest result" read, which are
-- ordered by creation time with the result id as the total tie-break.

CREATE INDEX ix_match_result_case_created_at
    ON match_result (invoice_case_id, created_at, id);
