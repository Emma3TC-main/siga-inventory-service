ALTER TABLE inventory.lot
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

ALTER TABLE inventory.asset
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- Admin lifecycle filter plus the fixed created_at DESC, id DESC ordering.
CREATE INDEX idx_lot_admin_status_created
    ON inventory.lot(status, created_at DESC, id DESC);
CREATE INDEX idx_asset_admin_status_created
    ON inventory.asset(operational_status, created_at DESC, id DESC);

-- Optional product filter plus the same deterministic admin ordering.
CREATE INDEX idx_lot_admin_product_created
    ON inventory.lot(product_id, created_at DESC, id DESC);
CREATE INDEX idx_asset_admin_product_created
    ON inventory.asset(product_id, created_at DESC, id DESC);

-- Location/availability remain derived from stock_balance. Existing
-- idx_stock_lot and uq_positive_asset_location cover those joins.
