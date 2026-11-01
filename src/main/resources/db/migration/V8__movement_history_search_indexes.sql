-- Fixed movement-history order and selective header lookup.
CREATE INDEX idx_movement_history_created
    ON inventory.movement(created_at DESC, id DESC);
CREATE INDEX idx_movement_requested_created
    ON inventory.movement(requested_by_user_id, created_at DESC, id DESC)
    WHERE requested_by_user_id IS NOT NULL;

-- SAME-row EXISTS predicates use the traceability dimension first and movement_id
-- to correlate the qualifying detail without loading movement details into Java.
CREATE INDEX idx_movement_detail_product_movement
    ON inventory.movement_detail(product_id, movement_id);
CREATE INDEX idx_movement_detail_lot_movement
    ON inventory.movement_detail(lot_id, movement_id)
    WHERE lot_id IS NOT NULL;
CREATE INDEX idx_movement_detail_asset_movement
    ON inventory.movement_detail(asset_id, movement_id)
    WHERE asset_id IS NOT NULL;
CREATE INDEX idx_movement_detail_origin_movement
    ON inventory.movement_detail(origin_location_id, movement_id)
    WHERE origin_location_id IS NOT NULL;
CREATE INDEX idx_movement_detail_destination_movement
    ON inventory.movement_detail(destination_location_id, movement_id)
    WHERE destination_location_id IS NOT NULL;
