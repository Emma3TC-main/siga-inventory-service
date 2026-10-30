package utp.siga.inventory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.dto.AssetPage;
import utp.siga.inventory.application.dto.LotPage;

@JdbcTest
@Testcontainers
class PostgresTraceabilityQueryTest {

    private static final UUID LOCATION_A = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID LOCATION_B = UUID.fromString("30000000-0000-0000-0000-000000000005");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_traceability_test")
            .withUsername("siga_inventory_traceability_test")
            .withPassword("traceability-test-only");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName());
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("inventory.test.expected-database", postgres::getDatabaseName);
        registry.add("inventory.test.expected-user", postgres::getUsername);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.schemas", () -> "inventory");
        registry.add("spring.flyway.default-schema", () -> "inventory");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void lotsIncludeOnlyActiveNonExpiredRowsAndUseCanonicalOrdering() {
        UUID productId = insertProduct("LOT-ELIGIBILITY");
        UUID nullNumber = insertLot(productId, null, null, null, "ACTIVE");
        UUID lotB = insertLot(productId, "B", "H2", TODAY, "ACTIVE");
        UUID lotA = insertLot(productId, "A", null, TODAY.plusDays(1), "ACTIVE");
        insertLot(productId, "EXPIRED-DATE", null, TODAY.minusDays(1), "ACTIVE");
        for (String status : List.of("QUARANTINED", "EXPIRED", "DEPLETED", "BLOCKED")) {
            insertLot(productId, status, null, null, status);
        }

        LotPage result = new PostgresLotQuery(jdbc)
                .findOperationalLots(productId, null, false, TODAY, 0, 20);

        assertEquals(3, result.totalElements());
        assertEquals(List.of(lotA, lotB, nullNumber), result.content().stream().map(lot -> lot.id()).toList());
        assertTrue(result.content().stream().allMatch(lot -> lot.locationId() == null));
        assertTrue(result.content().stream()
                .allMatch(lot -> lot.availableQuantity().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void lotsAggregateAvailabilityByOptionalLocationAndReturnStablePages() {
        UUID productId = insertProduct("LOT-AVAILABILITY");
        UUID first = insertLot(productId, "A", null, null, "ACTIVE");
        UUID second = insertLot(productId, "B", null, null, "ACTIVE");
        UUID third = insertLot(productId, "C", null, null, "ACTIVE");
        insertStock(productId, LOCATION_A, first, null, "2.500000");
        insertStock(productId, LOCATION_B, first, null, "1.000000");
        insertStock(productId, LOCATION_A, second, null, "0.000000");
        insertStock(productId, LOCATION_A, third, null, "4.000000");

        PostgresLotQuery query = new PostgresLotQuery(jdbc);
        LotPage all = query.findOperationalLots(productId, null, false, TODAY, 0, 2);
        LotPage availableAtA = query.findOperationalLots(productId, LOCATION_A, true, TODAY, 0, 20);

        assertEquals(3, all.totalElements());
        assertEquals(2, all.content().size());
        assertEquals(List.of(first, second), all.content().stream().map(lot -> lot.id()).toList());
        assertEquals(new BigDecimal("3.500000"), all.content().getFirst().availableQuantity());
        assertEquals(2, availableAtA.totalElements());
        assertEquals(List.of(first, third), availableAtA.content().stream().map(lot -> lot.id()).toList());
        assertTrue(availableAtA.content().stream().allMatch(lot -> LOCATION_A.equals(lot.locationId())));
        assertEquals(new BigDecimal("2.500000"), availableAtA.content().getFirst().availableQuantity());
    }

    @Test
    void assetsReturnOnlyOperationalUnassignedRows() {
        UUID productId = insertProduct("ASSET-UNASSIGNED");
        UUID expected = insertAsset(productId, "A-UNASSIGNED", "OPERATIVO");
        UUID stocked = insertAsset(productId, "B-STOCKED", "OPERATIVO");
        insertStock(productId, LOCATION_A, null, stocked, "1.000000");
        for (String status : List.of("EN_MANTENIMIENTO", "EN_TRANSITO", "INOPERATIVO", "FUERA_SERVICIO")) {
            insertAsset(productId, "Z-" + status, status);
        }

        AssetPage result = new PostgresAssetQuery(jdbc)
                .findOperationalAssets(productId, null, null, AssetAvailability.UNASSIGNED, 0, 20);

        assertEquals(1, result.totalElements());
        assertEquals(expected, result.content().getFirst().id());
        assertEquals(AssetOperationalStatus.OPERATIVO, result.content().getFirst().operationalStatus());
        assertFalse(result.content().getFirst().available());
        assertNull(result.content().getFirst().locationId());
        assertNull(result.content().getFirst().lotId());
    }

    @Test
    void assetsInStockHonorLocationLotOrderingAndPagination() {
        UUID productId = insertProduct("ASSET-IN-STOCK");
        UUID lotId = insertLot(productId, "ASSET-LOT", null, null, "ACTIVE");
        UUID first = insertAsset(productId, "A-ASSET", "OPERATIVO");
        UUID second = insertAsset(productId, "B-ASSET", "OPERATIVO");
        insertAsset(productId, "C-UNASSIGNED", "OPERATIVO");
        insertStock(productId, LOCATION_A, lotId, first, "1.000000");
        insertStock(productId, LOCATION_B, null, second, "1.000000");

        PostgresAssetQuery query = new PostgresAssetQuery(jdbc);
        AssetPage page = query.findOperationalAssets(productId, null, null,
                AssetAvailability.IN_STOCK, 0, 1);
        AssetPage filtered = query.findOperationalAssets(productId, LOCATION_A, lotId,
                AssetAvailability.IN_STOCK, 0, 20);
        AssetPage wrongLocation = query.findOperationalAssets(productId, LOCATION_B, lotId,
                AssetAvailability.IN_STOCK, 0, 20);
        AssetPage wrongLot = query.findOperationalAssets(productId, LOCATION_A, UUID.randomUUID(),
                AssetAvailability.IN_STOCK, 0, 20);

        assertEquals(2, page.totalElements());
        assertEquals(1, page.content().size());
        assertEquals(first, page.content().getFirst().id());
        assertTrue(page.content().getFirst().available());
        assertEquals(1, filtered.totalElements());
        assertEquals(first, filtered.content().getFirst().id());
        assertEquals(LOCATION_A, filtered.content().getFirst().locationId());
        assertEquals(lotId, filtered.content().getFirst().lotId());
        assertEquals(0, wrongLocation.totalElements());
        assertEquals(0, wrongLot.totalElements());
    }

    private UUID insertProduct(String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.product_ref(
                    product_id, sku, product_type, storage_unit_code, base_unit_code,
                    requires_lot, requires_heat_number, requires_serial, requires_expiry)
                VALUES (?, ?, 'MATERIAL', 'UND', 'UND', false, false, false, false)
                """, id, sku + "-" + id);
        return id;
    }

    private UUID insertLot(UUID productId, String lotNumber, String heatNumber,
            LocalDate expiresAt, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.lot(id, product_id, lot_number, heat_number, received_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, productId, lotNumber, heatNumber, TODAY.minusDays(2), expiresAt, status);
        return id;
    }

    private UUID insertAsset(UUID productId, String assetCode, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.asset(id, product_id, asset_code, serial_number, operational_status)
                VALUES (?, ?, ?, ?, ?)
                """, id, productId, assetCode + "-" + id, "SER-" + id, status);
        return id;
    }

    private void insertStock(UUID productId, UUID locationId, UUID lotId, UUID assetId, String quantity) {
        jdbc.update("""
                INSERT INTO inventory.stock_balance(product_id, location_id, lot_id, asset_id, quantity)
                VALUES (?, ?, ?, ?, ?::numeric)
                """, productId, locationId, lotId, assetId, quantity);
    }
}
