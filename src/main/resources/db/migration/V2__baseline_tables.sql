-- TODO: Pegar aquí los CREATE TABLE correspondientes al esquema 'inventory'.
-- REGLA: 0 FK hacia otros esquemas. Relaciones inter-dominio solo por ID.
-- ============================================================
-- INVENTORY / inventory-service
-- ============================================================
CREATE TABLE inventory.location (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(80) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    location_type varchar(30) NOT NULL CHECK (location_type IN (
        'ALMACEN','PATIO','ZONA','PASILLO','RACK','NIVEL','POSICION',
        'TALLER','RECEPCION','CUARENTENA','DESPACHO'
    )),
    parent_id uuid REFERENCES inventory.location(id),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX idx_location_parent ON inventory.location(parent_id);

-- Local projection of the Catalog data required by Inventory business rules.
CREATE TABLE inventory.supplier_ref (
    supplier_id uuid PRIMARY KEY,
    code varchar(50) NOT NULL UNIQUE,
    tax_id varchar(20) NOT NULL UNIQUE,
    business_name varchar(200) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    source_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inventory.product_ref (
    product_id uuid PRIMARY KEY,
    sku varchar(80) NOT NULL UNIQUE,
    product_type varchar(40) NOT NULL CHECK (product_type IN ('MATERIAL','INSUMO','REPUESTO','MAQUINARIA')),
    storage_unit_code varchar(20) NOT NULL,
    base_unit_code varchar(20) NOT NULL,
    min_stock numeric(18,6) NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    requires_lot boolean NOT NULL DEFAULT false,
    requires_heat_number boolean NOT NULL DEFAULT false,
    requires_serial boolean NOT NULL DEFAULT false,
    requires_expiry boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    source_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inventory.product_unit_conversion_ref (
    product_id uuid NOT NULL REFERENCES inventory.product_ref(product_id),
    from_unit_code varchar(20) NOT NULL,
    to_unit_code varchar(20) NOT NULL,
    factor numeric(18,6) NOT NULL CHECK (factor > 0),
    active boolean NOT NULL DEFAULT true,
    source_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, from_unit_code, to_unit_code),
    CHECK (from_unit_code <> to_unit_code)
);

CREATE TABLE inventory.lot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES inventory.product_ref(product_id),
    lot_number varchar(120),
    heat_number varchar(120),
    manufactured_at date,
    received_at date,
    expires_at date,
    status varchar(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','QUARANTINED','EXPIRED','DEPLETED','BLOCKED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, product_id),
    CHECK (expires_at IS NULL OR manufactured_at IS NULL OR expires_at >= manufactured_at)
);
CREATE UNIQUE INDEX uq_lot_product_number ON inventory.lot(product_id, lot_number) WHERE lot_number IS NOT NULL;
CREATE INDEX idx_lot_product ON inventory.lot(product_id);
CREATE INDEX idx_lot_expiry ON inventory.lot(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE inventory.asset (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES inventory.product_ref(product_id),
    asset_code varchar(80) NOT NULL UNIQUE,
    serial_number varchar(160) NOT NULL UNIQUE,
    brand varchar(100),
    model varchar(100),
    operational_status varchar(40) NOT NULL DEFAULT 'OPERATIVO' CHECK (operational_status IN (
        'OPERATIVO','EN_MANTENIMIENTO','EN_TRANSITO','INOPERATIVO','FUERA_SERVICIO'
    )),
    technical_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, product_id)
);
CREATE INDEX idx_asset_product ON inventory.asset(product_id);

-- The current location of a serialised asset is authoritative here, never duplicated in asset.
CREATE TABLE inventory.stock_balance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES inventory.product_ref(product_id),
    location_id uuid NOT NULL REFERENCES inventory.location(id),
    lot_id uuid,
    asset_id uuid,
    quantity numeric(18,6) NOT NULL CHECK (quantity >= 0),
    avg_cost_pen numeric(18,6) NOT NULL DEFAULT 0 CHECK (avg_cost_pen >= 0),
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (lot_id, product_id) REFERENCES inventory.lot(id, product_id),
    FOREIGN KEY (asset_id, product_id) REFERENCES inventory.asset(id, product_id),
    CHECK (asset_id IS NULL OR quantity IN (0,1))
);
CREATE UNIQUE INDEX uq_stock_dimension ON inventory.stock_balance(
    product_id,
    location_id,
    COALESCE(lot_id,'00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(asset_id,'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE UNIQUE INDEX uq_positive_asset_location ON inventory.stock_balance(asset_id)
    WHERE asset_id IS NOT NULL AND quantity > 0;
CREATE INDEX idx_stock_product_location ON inventory.stock_balance(product_id, location_id);
CREATE INDEX idx_stock_lot ON inventory.stock_balance(lot_id) WHERE lot_id IS NOT NULL;

CREATE TABLE inventory.movement (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    movement_code varchar(80) NOT NULL UNIQUE,
    movement_type varchar(30) NOT NULL CHECK (movement_type IN (
        'ENTRY','EXIT','TRANSFER','ADJUST_POSITIVE','ADJUST_NEGATIVE'
    )),
    status varchar(40) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT','PENDING_AUTHORIZATION','AUTHORIZED','CONFIRMED','REJECTED','CANCELLED'
    )),
    registered_by_user_id uuid NOT NULL,     -- external IAM reference
    requested_by_user_id uuid,              -- external IAM reference
    cost_center_code varchar(80),
    cost_center_name_snapshot varchar(160),
    supplier_id uuid REFERENCES inventory.supplier_ref(supplier_id),
    supplier_name_snapshot varchar(200),
    supplier_tax_id_snapshot varchar(20),
    reference_doc_type varchar(40),
    reference_doc_number varchar(120),
    reason varchar(255),
    notes text,
    is_sensitive boolean NOT NULL DEFAULT false,
    sensitivity_reason varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_at timestamptz,
    confirmed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT is_sensitive OR sensitivity_reason IS NOT NULL),
    CHECK (supplier_id IS NULL OR movement_type = 'ENTRY'),
    CHECK (supplier_id IS NULL OR (supplier_name_snapshot IS NOT NULL AND supplier_tax_id_snapshot IS NOT NULL))
);
CREATE INDEX idx_movement_status_created ON inventory.movement(status, created_at DESC);
CREATE INDEX idx_movement_type_created ON inventory.movement(movement_type, created_at DESC);
CREATE INDEX idx_movement_actor ON inventory.movement(registered_by_user_id, created_at DESC);
CREATE INDEX idx_movement_cost_center ON inventory.movement(cost_center_code, created_at DESC) WHERE cost_center_code IS NOT NULL;
CREATE INDEX idx_movement_supplier ON inventory.movement(supplier_id, created_at DESC) WHERE supplier_id IS NOT NULL;

CREATE TABLE inventory.movement_detail (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    movement_id uuid NOT NULL REFERENCES inventory.movement(id),
    line_number integer NOT NULL CHECK (line_number > 0),
    product_id uuid NOT NULL REFERENCES inventory.product_ref(product_id),
    entered_quantity numeric(18,6) NOT NULL CHECK (entered_quantity > 0),
    unit_code varchar(20) NOT NULL,
    conversion_factor numeric(18,6) NOT NULL DEFAULT 1 CHECK (conversion_factor > 0),
    stock_quantity numeric(18,6) NOT NULL CHECK (stock_quantity > 0),
    origin_location_id uuid REFERENCES inventory.location(id),
    destination_location_id uuid REFERENCES inventory.location(id),
    lot_id uuid,
    asset_id uuid,
    original_currency char(3) NOT NULL DEFAULT 'PEN',
    original_unit_cost numeric(18,6) CHECK (original_unit_cost IS NULL OR original_unit_cost >= 0),
    exchange_rate numeric(18,6) CHECK (exchange_rate IS NULL OR exchange_rate > 0),
    exchange_rate_date date,
    exchange_rate_source varchar(40),
    base_unit_cost_pen numeric(18,6) CHECK (base_unit_cost_pen IS NULL OR base_unit_cost_pen >= 0),
    applied_avg_cost_pen numeric(18,6) CHECK (applied_avg_cost_pen IS NULL OR applied_avg_cost_pen >= 0),
    FOREIGN KEY (lot_id, product_id) REFERENCES inventory.lot(id, product_id),
    FOREIGN KEY (asset_id, product_id) REFERENCES inventory.asset(id, product_id),
    UNIQUE (movement_id, line_number),
    CHECK (origin_location_id IS NULL OR destination_location_id IS NULL OR origin_location_id <> destination_location_id),
    CHECK (
        original_currency = 'PEN'
        OR (exchange_rate IS NOT NULL AND exchange_rate_date IS NOT NULL AND exchange_rate_source IS NOT NULL)
    )
);
CREATE INDEX idx_movement_detail_product ON inventory.movement_detail(product_id);
CREATE INDEX idx_movement_detail_origin ON inventory.movement_detail(origin_location_id) WHERE origin_location_id IS NOT NULL;
CREATE INDEX idx_movement_detail_destination ON inventory.movement_detail(destination_location_id) WHERE destination_location_id IS NOT NULL;

CREATE TABLE inventory.movement_authorization (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    movement_id uuid NOT NULL REFERENCES inventory.movement(id),
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    authorized_by_user_id uuid,              -- external IAM reference
    decision varchar(20) CHECK (decision IS NULL OR decision IN ('APPROVED','REJECTED')),
    step_up_verified boolean NOT NULL DEFAULT false,
    reason varchar(255),
    CHECK (
        (decision IS NULL AND decided_at IS NULL)
        OR (decision IS NOT NULL AND decided_at IS NOT NULL AND authorized_by_user_id IS NOT NULL)
    )
);
CREATE INDEX idx_authorization_pending ON inventory.movement_authorization(movement_id, requested_at DESC) WHERE decision IS NULL;

CREATE TABLE inventory.physical_count (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id uuid NOT NULL REFERENCES inventory.location(id),
    status varchar(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_PROGRESS','COMPLETED','CANCELLED')),
    counted_by_user_id uuid NOT NULL,         -- external IAM reference
    notes text,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE inventory.physical_count_line (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    count_id uuid NOT NULL REFERENCES inventory.physical_count(id),
    product_id uuid NOT NULL REFERENCES inventory.product_ref(product_id),
    lot_id uuid,
    asset_id uuid,
    system_quantity numeric(18,6) NOT NULL CHECK (system_quantity >= 0),
    counted_quantity numeric(18,6) NOT NULL CHECK (counted_quantity >= 0),
    difference_quantity numeric(18,6) GENERATED ALWAYS AS (counted_quantity - system_quantity) STORED,
    FOREIGN KEY (lot_id, product_id) REFERENCES inventory.lot(id, product_id),
    FOREIGN KEY (asset_id, product_id) REFERENCES inventory.asset(id, product_id)
);
CREATE INDEX idx_count_line_count ON inventory.physical_count_line(count_id);
CREATE INDEX idx_count_line_product ON inventory.physical_count_line(product_id);

CREATE TABLE inventory.idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key uuid NOT NULL UNIQUE,
    request_hash char(64) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id uuid,
    response_status integer NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_idempotency_expiry ON inventory.idempotency_record(expires_at);

-- Inventory consumes Catalog events. This table prevents duplicate projection effects.
CREATE TABLE inventory.processed_event (
    event_id uuid NOT NULL,
    consumer varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE TABLE inventory.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    correlation_id uuid,
    causation_id uuid,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_inventory_outbox_pending ON inventory.outbox_event(status, next_attempt_at, occurred_at) WHERE status='PENDING';
