CREATE INDEX idx_stock_location_positive
    ON inventory.stock_balance(location_id)
    WHERE quantity > 0;

CREATE INDEX idx_physical_count_location_open
    ON inventory.physical_count(location_id)
    WHERE status IN ('DRAFT', 'IN_PROGRESS');
