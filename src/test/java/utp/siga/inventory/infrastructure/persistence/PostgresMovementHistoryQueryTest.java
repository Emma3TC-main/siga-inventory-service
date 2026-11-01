package utp.siga.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.query.MovementHistoryFilter;
import utp.siga.inventory.domain.model.MovementStatus;
import utp.siga.inventory.domain.model.MovementType;

@JdbcTest
@Testcontainers
class PostgresMovementHistoryQueryTest {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_history_test")
            .withUsername("siga_inventory_history_test")
            .withPassword("history-test-only");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.schemas", () -> "inventory");
        registry.add("spring.flyway.default-schema", () -> "inventory");
        registry.add("inventory.test.expected-database", () -> "siga_inventory_history_test");
        registry.add("inventory.test.expected-user", () -> "siga_inventory_history_test");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void filtersEveryHeaderFieldAndUsesExclusiveUpperDateBound() {
        Instant created = Instant.parse("2026-10-20T10:15:30Z");
        UUID registered = UUID.randomUUID(), requested = UUID.randomUUID();
        UUID id = insertMovement("MOV-HISTORY-EXACT", MovementType.TRANSFER, MovementStatus.CONFIRMED,
                registered, requested, true, "REF-EXACT", created);
        insertMovement("MOV-HISTORY-OTHER", MovementType.ENTRY, MovementStatus.DRAFT,
                UUID.randomUUID(), null, false, null, created.minusSeconds(1));

        assertOnly(id, filters(f -> f.movementCode = "MOV-HISTORY-EXACT"));
        assertOnly(id, filters(f -> f.movementType = MovementType.TRANSFER));
        assertOnly(id, filters(f -> f.status = MovementStatus.CONFIRMED));
        assertOnly(id, filters(f -> f.registeredByUserId = registered));
        assertOnly(id, filters(f -> f.requestedByUserId = requested));
        assertOnly(id, filters(f -> f.sensitive = true));
        assertOnly(id, filters(f -> f.referenceDocNumber = "REF-EXACT"));
        assertOnly(id, filters(f -> f.from = created));
        assertThat(query(filters(f -> {
            f.movementCode = "MOV-HISTORY-EXACT";
            f.to = created;
        })).content()).isEmpty();
        assertOnly(id, filters(f -> {
            f.movementCode = "MOV-HISTORY-EXACT";
            f.to = created.plusMillis(1);
        }));
    }

    @Test
    void allDetailPredicatesMustMatchTheSameDetailRow() {
        DetailDimension first = dimension();
        DetailDimension second = dimension();
        UUID movement = insertMovement("MOV-SAME-ROW", MovementType.TRANSFER, MovementStatus.CONFIRMED,
                UUID.randomUUID(), null, false, null, Instant.parse("2026-10-21T00:00:00Z"));
        insertDetail(movement, 1, first);
        insertDetail(movement, 2, second);

        MovementPage crossRow = query(filters(f -> {
            f.productId = first.productId;
            f.lotId = second.lotId;
        }));
        assertThat(crossRow.content()).isEmpty();
        assertThat(crossRow.totalElements()).isZero();

        MovementPage sameRow = query(filters(f -> {
            f.productId = first.productId;
            f.lotId = first.lotId;
            f.assetId = first.assetId;
            f.sourceLocationId = first.originId;
            f.destinationLocationId = first.destinationId;
        }));
        assertThat(sameRow.content()).extracting(item -> item.id()).containsExactly(movement);
    }

    @Test
    void filtersProductLotAssetAndAllLocationFormsServerSide() {
        DetailDimension dimension = dimension();
        UUID movement = insertMovement("MOV-DIMENSIONS", MovementType.TRANSFER, MovementStatus.CONFIRMED,
                UUID.randomUUID(), null, false, null, Instant.parse("2026-10-22T00:00:00Z"));
        insertDetail(movement, 1, dimension);

        assertOnly(movement, filters(f -> f.productId = dimension.productId));
        assertOnly(movement, filters(f -> f.lotId = dimension.lotId));
        assertOnly(movement, filters(f -> f.assetId = dimension.assetId));
        assertOnly(movement, filters(f -> f.sourceLocationId = dimension.originId));
        assertOnly(movement, filters(f -> f.destinationLocationId = dimension.destinationId));
        assertOnly(movement, filters(f -> f.locationId = dimension.originId));
        assertOnly(movement, filters(f -> f.locationId = dimension.destinationId));
    }

    @Test
    void validUnknownIdentitiesReturnAnEmptyPage() {
        assertThat(query(filters(f -> f.productId = UUID.randomUUID())).content()).isEmpty();
        assertThat(query(filters(f -> f.lotId = UUID.randomUUID())).content()).isEmpty();
        assertThat(query(filters(f -> f.assetId = UUID.randomUUID())).content()).isEmpty();
        assertThat(query(filters(f -> f.locationId = UUID.randomUUID())).content()).isEmpty();
        assertThat(query(filters(f -> f.requestedByUserId = UUID.randomUUID())).content()).isEmpty();
    }

    @Test
    void equalDateBoundsAreAValidEmptyInterval() {
        Instant instant = Instant.parse("2026-10-23T00:00:00Z");
        insertMovement("MOV-EQUAL-DATE", MovementType.ENTRY, MovementStatus.DRAFT, UUID.randomUUID(), null,
                false, null, instant);
        MovementPage page = query(filters(f -> {
            f.from = instant;
            f.to = instant;
        }));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void paginatesInCreatedAtAndIdDescendingOrderWithAccurateTotal() {
        Instant sameTime = Instant.parse("2026-10-24T00:00:00Z");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        insertMovement(high, "MOV-ORDER-HIGH", sameTime);
        insertMovement(low, "MOV-ORDER-LOW", sameTime);

        MovementPage first = query(filters(f -> f.size = 1));
        MovementPage second = query(filters(f -> { f.page = 1; f.size = 1; }));
        assertThat(first.content()).extracting(item -> item.id()).containsExactly(high);
        assertThat(second.content()).extracting(item -> item.id()).containsExactly(low);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
    }

    @Test
    void supportsMaximumPageSizeAndEmptyPageBeyondRange() {
        UUID id = insertMovement("MOV-PAGE", MovementType.ENTRY, MovementStatus.DRAFT, UUID.randomUUID(), null,
                false, null, Instant.parse("2026-10-25T00:00:00Z"));
        MovementPage maximum = query(filters(f -> f.size = 100));
        assertThat(maximum.content()).extracting(item -> item.id()).containsExactly(id);
        assertThat(query(filters(f -> f.page = 10)).content()).isEmpty();
    }

    @Test
    void findsAProductMatchBeyondTheFirstHundredMovements() {
        DetailDimension targetDimension = dimension();
        Instant base = Instant.parse("2026-10-30T00:00:00Z");
        for (int index = 0; index < 101; index++) {
            insertMovement("MOV-RECENT-" + index, MovementType.ENTRY, MovementStatus.DRAFT,
                    UUID.randomUUID(), null, false, null, base.minusSeconds(index));
        }
        UUID target = insertMovement("MOV-BEYOND-100", MovementType.ENTRY, MovementStatus.CONFIRMED,
                UUID.randomUUID(), null, false, null, base.minusSeconds(200));
        insertDetail(target, 1, targetDimension);

        MovementPage unfilteredFirstPage = query(filters(f -> f.size = 100));
        assertThat(unfilteredFirstPage.content()).extracting(item -> item.id()).doesNotContain(target);
        assertOnly(target, filters(f -> f.productId = targetDimension.productId));
    }

    @Test
    void listProjectionIsSummaryOnlyAndPreservesCanonicalFields() {
        UUID id = insertMovement("MOV-SUMMARY", MovementType.ADJUST_POSITIVE, MovementStatus.AUTHORIZED,
                UUID.randomUUID(), UUID.randomUUID(), true, "DOC-1", Instant.parse("2026-10-26T00:00:00Z"));
        var item = query(filters(f -> f.movementCode = "MOV-SUMMARY")).content().getFirst();
        assertThat(item.id()).isEqualTo(id);
        assertThat(item.costCenterCode()).isEqualTo("CC-01");
        assertThat(item.costCenterNameSnapshot()).isEqualTo("Operaciones");
        assertThat(item.referenceDocType()).isEqualTo("PHYSICAL_COUNT");
        assertThat(item.referenceDocNumber()).isEqualTo("DOC-1");
        assertThat(item.reason()).isEqualTo("Ajuste controlado");
    }

    @Test
    void explainPlansAlignCriticalFiltersWithV8Indexes() {
        DetailDimension dimension = dimension();
        UUID requested = UUID.randomUUID();
        UUID movement = insertMovement("MOV-EXPLAIN", MovementType.TRANSFER, MovementStatus.CONFIRMED,
                UUID.randomUUID(), requested, false, null, Instant.parse("2026-10-27T00:00:00Z"));
        insertDetail(movement, 1, dimension);
        jdbc.execute("SET LOCAL enable_seqscan = off");

        assertThat(plan("SELECT id FROM inventory.movement ORDER BY created_at DESC, id DESC LIMIT 20"))
                .contains("idx_movement_history_created");
        assertThat(plan(detailExplain("product_id"), dimension.productId))
                .contains("idx_movement_detail_product_movement");
        assertThat(plan(detailExplain("lot_id"), dimension.lotId))
                .contains("idx_movement_detail_lot_movement");
        assertThat(plan(detailExplain("asset_id"), dimension.assetId))
                .contains("idx_movement_detail_asset_movement");
        assertThat(plan("""
                SELECT m.id FROM inventory.movement m
                WHERE EXISTS (SELECT 1 FROM inventory.movement_detail d WHERE d.movement_id=m.id
                  AND (d.origin_location_id=? OR d.destination_location_id=?))
                ORDER BY m.created_at DESC, m.id DESC LIMIT 20
                """, dimension.originId, dimension.originId))
                .contains("idx_movement_detail_origin_movement")
                .contains("idx_movement_detail_destination_movement");
        assertThat(plan("""
                SELECT id FROM inventory.movement WHERE requested_by_user_id=?
                ORDER BY created_at DESC, id DESC LIMIT 20
                """, requested)).contains("idx_movement_requested_created");
        assertThat(plan("""
                SELECT m.id FROM inventory.movement m
                WHERE EXISTS (SELECT 1 FROM inventory.movement_detail d WHERE d.movement_id=m.id
                  AND d.product_id=? AND d.lot_id=? AND d.asset_id=?)
                ORDER BY m.created_at DESC, m.id DESC LIMIT 20
                """, dimension.productId, dimension.lotId, dimension.assetId))
                .contains("idx_movement_detail_asset_movement");
    }

    private MovementPage query(MovementHistoryFilter filter) {
        return new PostgresMovementQuery(jdbc).getMovements(filter);
    }

    private String detailExplain(String column) {
        return "SELECT m.id FROM inventory.movement m WHERE EXISTS (SELECT 1 FROM inventory.movement_detail d "
                + "WHERE d.movement_id=m.id AND d." + column + "=?) "
                + "ORDER BY m.created_at DESC, m.id DESC LIMIT 20";
    }

    private String plan(String sql, Object... arguments) {
        return String.join("\n", jdbc.query("EXPLAIN (COSTS OFF) " + sql,
                (result, row) -> result.getString(1), arguments));
    }

    private void assertOnly(UUID id, MovementHistoryFilter filter) {
        MovementPage page = query(filter);
        assertThat(page.content()).extracting(item -> item.id()).containsExactly(id);
        assertThat(page.totalElements()).isOne();
    }

    private MovementHistoryFilter filters(Consumer<FilterValues> customization) {
        FilterValues values = new FilterValues();
        customization.accept(values);
        return values.build();
    }

    private UUID insertMovement(String code, MovementType type, MovementStatus status, UUID registered, UUID requested,
            boolean sensitive, String reference, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.movement(id, movement_code, movement_type, status, registered_by_user_id,
                    requested_by_user_id, cost_center_code, cost_center_name_snapshot, reference_doc_type,
                    reference_doc_number, reason, is_sensitive, sensitivity_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CC-01', 'Operaciones', 'PHYSICAL_COUNT', ?, 'Ajuste controlado',
                    ?, ?, ?, ?)
                """, id, code, type.name(), status.name(), registered, requested, reference, sensitive,
                sensitive ? "SENSITIVE_TEST" : null, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private void insertMovement(UUID id, String code, Instant createdAt) {
        jdbc.update("""
                INSERT INTO inventory.movement(id, movement_code, movement_type, status, registered_by_user_id,
                    is_sensitive, created_at, updated_at)
                VALUES (?, ?, 'ENTRY', 'DRAFT', ?, false, ?, ?)
                """, id, code, UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private DetailDimension dimension() {
        long sequence = SEQUENCE.incrementAndGet();
        UUID product = UUID.randomUUID(), lot = UUID.randomUUID(), asset = UUID.randomUUID();
        UUID origin = UUID.randomUUID(), destination = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.product_ref(product_id, sku, product_type, storage_unit_code, base_unit_code)
                VALUES (?, ?, 'MATERIAL', 'EA', 'EA')
                """, product, "HIST-SKU-" + sequence);
        jdbc.update("INSERT INTO inventory.location(id, code, name, location_type) VALUES (?, ?, ?, 'ALMACEN')",
                origin, "HIST-O-" + sequence, "Origen " + sequence);
        jdbc.update("INSERT INTO inventory.location(id, code, name, location_type) VALUES (?, ?, ?, 'ALMACEN')",
                destination, "HIST-D-" + sequence, "Destino " + sequence);
        jdbc.update("INSERT INTO inventory.lot(id, product_id, lot_number) VALUES (?, ?, ?)",
                lot, product, "HIST-LOT-" + sequence);
        jdbc.update("""
                INSERT INTO inventory.asset(id, product_id, asset_code, serial_number)
                VALUES (?, ?, ?, ?)
                """, asset, product, "HIST-ASSET-" + sequence, "HIST-SERIAL-" + sequence);
        return new DetailDimension(product, lot, asset, origin, destination);
    }

    private void insertDetail(UUID movement, int line, DetailDimension dimension) {
        jdbc.update("""
                INSERT INTO inventory.movement_detail(id, movement_id, line_number, product_id, entered_quantity,
                    unit_code, conversion_factor, stock_quantity, origin_location_id, destination_location_id,
                    lot_id, asset_id, original_currency)
                VALUES (?, ?, ?, ?, 1, 'EA', 1, 1, ?, ?, ?, ?, 'PEN')
                """, UUID.randomUUID(), movement, line, dimension.productId, dimension.originId,
                dimension.destinationId, dimension.lotId, dimension.assetId);
    }

    private record DetailDimension(UUID productId, UUID lotId, UUID assetId, UUID originId, UUID destinationId) {}

    private static final class FilterValues {
        int page;
        int size = 20;
        String movementCode;
        MovementType movementType;
        MovementStatus status;
        UUID productId;
        UUID lotId;
        UUID assetId;
        UUID sourceLocationId;
        UUID destinationLocationId;
        UUID locationId;
        UUID registeredByUserId;
        UUID requestedByUserId;
        Instant from;
        Instant to;
        Boolean sensitive;
        String referenceDocNumber;

        MovementHistoryFilter build() {
            return new MovementHistoryFilter(page, size, movementCode, movementType, status, productId, lotId,
                    assetId, sourceLocationId, destinationLocationId, locationId, registeredByUserId,
                    requestedByUserId, from, to, sensitive, referenceDocNumber);
        }
    }
}
