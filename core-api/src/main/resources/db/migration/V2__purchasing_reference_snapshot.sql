-- P1-02 external purchasing reference snapshots.
--
-- The external purchasing system is the source of truth for purchase orders,
-- receipts and their external versions. These tables hold only the current
-- local snapshot of that external truth and are brought up to date atomically
-- when a newer external aggregate snapshot version arrives.
--
-- Row identity is stable: a row is keyed by its external natural key and is
-- updated in place rather than deleted and re-created. Rows that disappear from
-- a newer snapshot are kept and marked inactive, so future ReceiptAllocation
-- foreign keys and row locks stay stable. Current reads must select only active
-- rows. They deliberately do not model ReceiptAllocation, which is the local
-- record of consumed receipt quantity and belongs to a later ticket.
--
-- The aggregate-level snapshot version and the purchase order fact version are
-- separate queryable columns. Receipt and receipt line fact versions are kept
-- on their own rows. confirmed_quantity is the externally confirmed receipt
-- quantity and may legitimately be zero, so it is kept distinct from the
-- positive ordered_quantity used for purchase order lines.

CREATE TABLE purchase_order_snapshot (
    purchase_order_id varchar(64) PRIMARY KEY,
    supplier_id varchar(64) NOT NULL,
    supplier_name varchar(200) NOT NULL,
    status varchar(32) NOT NULL,
    snapshot_version bigint NOT NULL,
    purchase_order_version bigint NOT NULL,
    payload_hash varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    retrieved_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_purchase_order_snapshot_status CHECK (status IN ('CONFIRMED', 'UNCONFIRMED')),
    CONSTRAINT ck_purchase_order_snapshot_snapshot_version CHECK (snapshot_version >= 0),
    CONSTRAINT ck_purchase_order_snapshot_purchase_order_version CHECK (purchase_order_version >= 0)
);

CREATE TABLE purchase_order_line_snapshot (
    id uuid PRIMARY KEY,
    purchase_order_id varchar(64) NOT NULL REFERENCES purchase_order_snapshot (purchase_order_id),
    purchase_order_line_id varchar(64) NOT NULL,
    item_id varchar(64) NOT NULL,
    item_name varchar(500) NOT NULL,
    ordered_quantity integer NOT NULL,
    unit_price bigint NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_purchase_order_line_ordered_quantity CHECK (ordered_quantity > 0),
    CONSTRAINT ck_purchase_order_line_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ux_purchase_order_line_external UNIQUE (purchase_order_id, purchase_order_line_id)
);

CREATE TABLE receipt_snapshot (
    id uuid PRIMARY KEY,
    purchase_order_id varchar(64) NOT NULL REFERENCES purchase_order_snapshot (purchase_order_id),
    receipt_id varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    receipt_date date NOT NULL,
    receipt_version bigint NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_receipt_snapshot_status CHECK (status IN ('CONFIRMED', 'UNCONFIRMED')),
    CONSTRAINT ck_receipt_snapshot_version CHECK (receipt_version >= 0),
    CONSTRAINT ux_receipt_snapshot_external UNIQUE (purchase_order_id, receipt_id),
    CONSTRAINT ux_receipt_snapshot_receipt_po UNIQUE (receipt_id, purchase_order_id)
);

-- A receipt line may only reference a purchase order line of the same purchase
-- order, and only a receipt that belongs to that same purchase order. The
-- composite foreign keys make both references structurally valid at the DB.
-- Inactive rows are preserved so these references never dangle.
CREATE TABLE receipt_line_snapshot (
    id uuid PRIMARY KEY,
    purchase_order_id varchar(64) NOT NULL,
    receipt_id varchar(64) NOT NULL,
    receipt_line_id varchar(64) NOT NULL,
    purchase_order_line_id varchar(64) NOT NULL,
    receipt_line_version bigint NOT NULL,
    confirmed_quantity integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_receipt_line_confirmed_quantity CHECK (confirmed_quantity >= 0),
    CONSTRAINT ck_receipt_line_version CHECK (receipt_line_version >= 0),
    CONSTRAINT ux_receipt_line_snapshot_external UNIQUE (receipt_id, receipt_line_id),
    CONSTRAINT fk_receipt_line_receipt_same_po
        FOREIGN KEY (receipt_id, purchase_order_id)
        REFERENCES receipt_snapshot (receipt_id, purchase_order_id),
    CONSTRAINT fk_receipt_line_po_line_same_po
        FOREIGN KEY (purchase_order_id, purchase_order_line_id)
        REFERENCES purchase_order_line_snapshot (purchase_order_id, purchase_order_line_id)
);
