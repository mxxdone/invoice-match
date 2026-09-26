-- P1-05 human review workflow: case-local item mapping, deterministic re-match,
-- supplement/reject and immutable ReviewSnapshot with freshness evaluation.
--
-- The review snapshot, decision, match result and idempotency tables already
-- exist from P1-01/P1-03/P1-04. This migration adds the facts a reviewer and
-- P1-07 approval need without parsing canonical JSON:
--
-- 1. match_result records the exact purchasing snapshot it compared and the
--    mapping watermark that was applied, so a review snapshot can be tied to a
--    source result by columns rather than by reading the frozen payload.
-- 2. review_snapshot gets an unambiguous per-case monotonic snapshot number
--    (created_at plus a random UUID does not define business order), plus the
--    purchasing snapshot version/hash and mapping watermark it froze.
-- 3. review_decision gets a per-case monotonic decision number and normalized
--    mapping fields (bundle, invoice line number, chosen item and the purchase
--    order line it resolved to) with foreign keys and checks so effective
--    mapping resolution is queryable and cannot point across a case boundary.

-- ---------------------------------------------------------------------------
-- 1. match_result source facts
-- ---------------------------------------------------------------------------

ALTER TABLE match_result
    ADD COLUMN purchasing_snapshot_version bigint,
    ADD COLUMN purchasing_snapshot_hash varchar(128),
    ADD COLUMN mapping_watermark integer;

-- Existing P1-04 results already embed the purchasing snapshot in the canonical
-- payload, so backfill it before the columns become mandatory. No mapping
-- decisions could exist before this ticket, so their watermark is zero.
UPDATE match_result
   SET purchasing_snapshot_version = (payload -> 'purchasingSnapshot' ->> 'snapshotVersion')::bigint,
       purchasing_snapshot_hash = payload -> 'purchasingSnapshot' ->> 'payloadHash',
       mapping_watermark = 0;

ALTER TABLE match_result
    ALTER COLUMN purchasing_snapshot_version SET NOT NULL,
    ALTER COLUMN purchasing_snapshot_hash SET NOT NULL,
    ALTER COLUMN mapping_watermark SET NOT NULL,
    ADD CONSTRAINT ck_match_result_purchasing_snapshot_version
        CHECK (purchasing_snapshot_version >= 0),
    ADD CONSTRAINT ck_match_result_mapping_watermark CHECK (mapping_watermark >= 0);

-- ---------------------------------------------------------------------------
-- 2. review_snapshot ordering and captured source facts
-- ---------------------------------------------------------------------------

ALTER TABLE review_snapshot
    ADD COLUMN snapshot_number integer,
    ADD COLUMN match_result_number integer,
    ADD COLUMN purchasing_snapshot_version bigint,
    ADD COLUMN purchasing_snapshot_hash varchar(128),
    ADD COLUMN mapping_watermark integer;

WITH numbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY invoice_case_id ORDER BY created_at, id) AS rn
      FROM review_snapshot
)
UPDATE review_snapshot rs
   SET snapshot_number = n.rn,
       match_result_number = CASE WHEN rs.match_result_id IS NULL THEN NULL ELSE 1 END,
       purchasing_snapshot_version = 0,
       purchasing_snapshot_hash = 'legacy',
       mapping_watermark = 0
  FROM numbered n
 WHERE n.id = rs.id;

ALTER TABLE review_snapshot
    ALTER COLUMN snapshot_number SET NOT NULL,
    ALTER COLUMN purchasing_snapshot_version SET NOT NULL,
    ALTER COLUMN purchasing_snapshot_hash SET NOT NULL,
    ALTER COLUMN mapping_watermark SET NOT NULL,
    ADD CONSTRAINT ck_review_snapshot_number CHECK (snapshot_number > 0),
    ADD CONSTRAINT ck_review_snapshot_match_result_number
        CHECK (match_result_number IS NULL OR match_result_number > 0),
    -- The source match result id and its append number are captured together so
    -- a snapshot can never reference a result without its business order.
    ADD CONSTRAINT ck_review_snapshot_match_result_pair
        CHECK ((match_result_id IS NULL) = (match_result_number IS NULL)),
    ADD CONSTRAINT ck_review_snapshot_purchasing_version CHECK (purchasing_snapshot_version >= 0),
    ADD CONSTRAINT ck_review_snapshot_mapping_watermark CHECK (mapping_watermark >= 0),
    ADD CONSTRAINT ux_review_snapshot_case_number UNIQUE (invoice_case_id, snapshot_number);

CREATE INDEX ix_review_snapshot_case_number
    ON review_snapshot (invoice_case_id, snapshot_number);

-- ---------------------------------------------------------------------------
-- 3. review_decision ordering and normalized mapping facts
-- ---------------------------------------------------------------------------

ALTER TABLE review_decision
    ADD COLUMN decision_number integer,
    ADD COLUMN mapping_bundle_id uuid,
    ADD COLUMN mapping_line_number integer,
    ADD COLUMN mapping_item_id varchar(64),
    ADD COLUMN mapping_po_line_id varchar(64);

WITH numbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY invoice_case_id ORDER BY decided_at, id) AS rn
      FROM review_decision
)
UPDATE review_decision rd
   SET decision_number = n.rn
  FROM numbered n
 WHERE n.id = rd.id;

ALTER TABLE review_decision
    ALTER COLUMN decision_number SET NOT NULL,
    ADD CONSTRAINT ck_review_decision_number CHECK (decision_number > 0),
    ADD CONSTRAINT ck_review_decision_mapping_line
        CHECK (mapping_line_number IS NULL OR mapping_line_number > 0),
    -- A MAPPING decision carries the full normalized mapping target; every other
    -- decision type carries none, so effective resolution never sees a partial
    -- mapping row.
    ADD CONSTRAINT ck_review_decision_mapping_fields CHECK (
        (decision = 'MAPPING'
            AND mapping_bundle_id IS NOT NULL
            AND mapping_line_number IS NOT NULL
            AND mapping_item_id IS NOT NULL
            AND mapping_po_line_id IS NOT NULL)
        OR (decision <> 'MAPPING'
            AND mapping_bundle_id IS NULL
            AND mapping_line_number IS NULL
            AND mapping_item_id IS NULL
            AND mapping_po_line_id IS NULL)),
    ADD CONSTRAINT ux_review_decision_case_number UNIQUE (invoice_case_id, decision_number),
    ADD CONSTRAINT fk_review_decision_mapping_bundle_same_case
        FOREIGN KEY (mapping_bundle_id, invoice_case_id)
        REFERENCES evidence_bundle (id, invoice_case_id);

CREATE INDEX ix_review_decision_case_number
    ON review_decision (invoice_case_id, decision_number);

CREATE INDEX ix_review_decision_mapping_lookup
    ON review_decision (invoice_case_id, mapping_bundle_id, mapping_line_number, decision_number);
