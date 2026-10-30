ALTER TABLE inventory.physical_count
    RENAME COLUMN counted_by_user_id TO created_by_user_id;

ALTER TABLE inventory.physical_count
    ADD COLUMN completed_by_user_id uuid,
    ADD COLUMN cancelled_by_user_id uuid,
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancellation_reason varchar(255),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE inventory.physical_count
    ALTER COLUMN started_at DROP NOT NULL;

ALTER TABLE inventory.physical_count
    DROP CONSTRAINT IF EXISTS physical_count_check;

ALTER TABLE inventory.physical_count
    ADD CONSTRAINT chk_physical_count_version CHECK (version >= 0),
    ADD CONSTRAINT chk_physical_count_times CHECK (
        (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
        AND (cancelled_at IS NULL OR cancelled_at >= created_at)
    ),
    ADD CONSTRAINT chk_physical_count_terminal_data CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND completed_by_user_id IS NOT NULL
            AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by_user_id IS NOT NULL
            AND cancellation_reason IS NOT NULL AND completed_at IS NULL AND completed_by_user_id IS NULL)
        OR (status IN ('DRAFT', 'IN_PROGRESS') AND completed_at IS NULL AND completed_by_user_id IS NULL
            AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancellation_reason IS NULL)
    );

CREATE INDEX idx_physical_count_created
    ON inventory.physical_count(created_at DESC, id DESC);

ALTER TABLE inventory.physical_count_line
    ALTER COLUMN counted_quantity DROP NOT NULL,
    ADD COLUMN notes text,
    ADD COLUMN counted_by_user_id uuid,
    ADD COLUMN counted_at timestamptz;

ALTER TABLE inventory.physical_count_line
    ADD CONSTRAINT chk_physical_count_line_capture CHECK (
        (counted_quantity IS NULL AND counted_by_user_id IS NULL AND counted_at IS NULL)
        OR (counted_quantity IS NOT NULL AND counted_by_user_id IS NOT NULL AND counted_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_physical_count_asset_quantities CHECK (
        asset_id IS NULL OR (system_quantity IN (0, 1) AND (counted_quantity IS NULL OR counted_quantity IN (0, 1)))
    );

CREATE UNIQUE INDEX uq_physical_count_dimension
    ON inventory.physical_count_line(
        count_id,
        product_id,
        COALESCE(lot_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(asset_id, '00000000-0000-0000-0000-000000000000'::uuid)
    );
