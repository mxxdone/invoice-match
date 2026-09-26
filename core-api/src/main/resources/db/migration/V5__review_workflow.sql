-- P1-05 human review workflow: case-local item mapping, deterministic re-match,
-- supplement/reject and immutable ReviewSnapshot with freshness.
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
--    match result number, purchasing snapshot version/hash and mapping
--    watermark it froze.
-- 3. review_decision gets a per-case monotonic decision number and normalized
--    mapping fields (bundle, invoice line number, chosen item and the purchase
--    order line it resolved to) with foreign keys and checks so effective
--    mapping resolution is queryable and cannot point across a case boundary.
--
-- The backfills below update append-only tables, so they temporarily disable
-- the V1 immutability triggers. This is the only sanctioned write to those rows
-- and it runs inside the migration transaction: the triggers are re-enabled
-- before the migration commits, so no later application write can bypass them.

-- ---------------------------------------------------------------------------
-- 1. match_result source facts
-- ---------------------------------------------------------------------------

ALTER TABLE match_result
    ADD COLUMN purchasing_snapshot_version bigint,
    ADD COLUMN purchasing_snapshot_hash varchar(128),
    ADD COLUMN mapping_watermark integer;

-- Existing P1-04 results already embed the purchasing snapshot in the canonical
-- payload, so backfill it before the columns become mandatory. No mapping
-- decisions could exist before this ticket, so their watermark is zero. The
-- defaults keep a malformed historical row from blocking an upgrade.
ALTER TABLE match_result DISABLE TRIGGER trg_match_result_immutable;

UPDATE match_result
   SET purchasing_snapshot_version =
           COALESCE((payload -> 'purchasingSnapshot' ->> 'snapshotVersion')::bigint, 0),
       purchasing_snapshot_hash =
           COALESCE(payload -> 'purchasingSnapshot' ->> 'payloadHash', 'legacy'),
       mapping_watermark = 0;

ALTER TABLE match_result ENABLE TRIGGER trg_match_result_immutable;

ALTER TABLE match_result
    ALTER COLUMN purchasing_snapshot_version SET NOT NULL,
    ALTER COLUMN purchasing_snapshot_hash SET NOT NULL,
    ALTER COLUMN mapping_watermark SET NOT NULL,
    ADD CONSTRAINT ck_match_result_purchasing_snapshot_version
        CHECK (purchasing_snapshot_version >= 0),
    ADD CONSTRAINT ck_match_result_mapping_watermark CHECK (mapping_watermark >= 0),
    -- Composite key a review snapshot references so its captured purchasing
    -- version/hash, mapping watermark, bundle and result number provably match
    -- the source match result.
    ADD CONSTRAINT ux_match_result_source UNIQUE (
        id,
        invoice_case_id,
        result_number,
        evidence_bundle_id,
        purchasing_snapshot_version,
        purchasing_snapshot_hash,
        mapping_watermark);

-- ---------------------------------------------------------------------------
-- 2. review_snapshot ordering and captured source facts
-- ---------------------------------------------------------------------------

ALTER TABLE review_snapshot
    ADD COLUMN snapshot_number integer,
    ADD COLUMN match_result_number integer,
    ADD COLUMN purchasing_snapshot_version bigint,
    ADD COLUMN purchasing_snapshot_hash varchar(128),
    ADD COLUMN mapping_watermark integer;

-- Derive the captured source facts from the referenced match result when there
-- is one, so the composite foreign key added below is already satisfied by the
-- backfilled rows. Snapshots without a source result keep legacy defaults.
ALTER TABLE review_snapshot DISABLE TRIGGER trg_review_snapshot_immutable;

WITH numbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY invoice_case_id ORDER BY created_at, id) AS rn
      FROM review_snapshot
)
UPDATE review_snapshot rs
   SET snapshot_number = n.rn,
       match_result_number = (SELECT mr.result_number FROM match_result mr WHERE mr.id = rs.match_result_id),
       purchasing_snapshot_version = COALESCE(
           (SELECT mr.purchasing_snapshot_version FROM match_result mr WHERE mr.id = rs.match_result_id), 0),
       purchasing_snapshot_hash = COALESCE(
           (SELECT mr.purchasing_snapshot_hash FROM match_result mr WHERE mr.id = rs.match_result_id), 'legacy'),
       mapping_watermark = COALESCE(
           (SELECT mr.mapping_watermark FROM match_result mr WHERE mr.id = rs.match_result_id), 0)
  FROM numbered n
 WHERE n.id = rs.id;

ALTER TABLE review_snapshot ENABLE TRIGGER trg_review_snapshot_immutable;

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
    ADD CONSTRAINT ux_review_snapshot_case_number UNIQUE (invoice_case_id, snapshot_number),
    -- Composite keys referenced by review_decision so a mapping decision's
    -- target bundle is provably the bundle of the snapshot it targeted.
    ADD CONSTRAINT ux_review_snapshot_id_case_bundle UNIQUE (id, invoice_case_id, evidence_bundle_id),
    -- The captured purchasing version/hash, mapping watermark, bundle and result
    -- number must equal the source match result exactly. Nullable match_result_id
    -- rows (no source) are exempt under MATCH SIMPLE semantics.
    ADD CONSTRAINT fk_review_snapshot_source
        FOREIGN KEY (
            match_result_id,
            invoice_case_id,
            match_result_number,
            evidence_bundle_id,
            purchasing_snapshot_version,
            purchasing_snapshot_hash,
            mapping_watermark)
        REFERENCES match_result (
            id,
            invoice_case_id,
            result_number,
            evidence_bundle_id,
            purchasing_snapshot_version,
            purchasing_snapshot_hash,
            mapping_watermark);

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

ALTER TABLE review_decision DISABLE TRIGGER trg_review_decision_immutable;

WITH numbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY invoice_case_id ORDER BY decided_at, id) AS rn
      FROM review_decision
)
UPDATE review_decision rd
   SET decision_number = n.rn
  FROM numbered n
 WHERE n.id = rd.id;

ALTER TABLE review_decision ENABLE TRIGGER trg_review_decision_immutable;

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
        REFERENCES evidence_bundle (id, invoice_case_id),
    -- The mapping target bundle must be exactly the bundle of the snapshot the
    -- human saw, not merely a bundle of the same case.
    ADD CONSTRAINT fk_review_decision_mapping_bundle_matches_snapshot
        FOREIGN KEY (review_snapshot_id, invoice_case_id, mapping_bundle_id)
        REFERENCES review_snapshot (id, invoice_case_id, evidence_bundle_id);

CREATE INDEX ix_review_decision_case_number
    ON review_decision (invoice_case_id, decision_number);

CREATE INDEX ix_review_decision_mapping_lookup
    ON review_decision (invoice_case_id, mapping_bundle_id, mapping_line_number, decision_number);
