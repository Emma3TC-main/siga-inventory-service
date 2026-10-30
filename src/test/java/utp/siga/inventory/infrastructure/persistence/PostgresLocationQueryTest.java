package utp.siga.inventory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.dto.LocationView;

@JdbcTest
@Testcontainers
class PostgresLocationQueryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_location_test")
            .withUsername("siga_inventory_location_test")
            .withPassword("location-test-only");

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
    void returnsSeedCompatibleActiveLocationsWithNullableParentInDeterministicOrder() {
        List<LocationView> locations = new PostgresLocationQuery(jdbc).findActiveLocations();

        assertEquals(List.of("ALM-01", "PATIO-01", "POS-A01", "POS-M01", "ZONA-A"),
                locations.stream().map(LocationView::code).toList());
        LocationView root = locations.stream().filter(location -> location.code().equals("ALM-01"))
                .findFirst().orElseThrow();
        LocationView child = locations.stream().filter(location -> location.code().equals("POS-A01"))
                .findFirst().orElseThrow();
        assertNull(root.parentId());
        assertEquals(LocationType.ALMACEN, root.locationType());
        assertEquals(UUID.fromString("30000000-0000-0000-0000-000000000002"), child.parentId());
        assertEquals(LocationType.POSICION, child.locationType());
        assertTrue(locations.stream().allMatch(LocationView::active));
    }

    @Test
    void excludesInactiveLocationsAndMapsEveryCanonicalType() {
        UUID inactiveId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.location(id, code, name, location_type, active)
                VALUES (?, 'ZZ-INACTIVE', 'Inactive fixture', 'ALMACEN', false)
                """, inactiveId);

        List<UUID> fixtureIds = new ArrayList<>();
        int sequence = 0;
        for (LocationType type : LocationType.values()) {
            UUID id = UUID.randomUUID();
            fixtureIds.add(id);
            jdbc.update("""
                    INSERT INTO inventory.location(id, code, name, location_type, active)
                    VALUES (?, ?, ?, ?, true)
                    """, id, "ZZ-TYPE-" + String.format("%02d", sequence++), type.name(), type.name());
        }

        List<LocationView> locations = new PostgresLocationQuery(jdbc).findActiveLocations();

        assertFalse(locations.stream().anyMatch(location -> location.id().equals(inactiveId)));
        assertEquals(List.of(LocationType.values()), locations.stream()
                .filter(location -> fixtureIds.contains(location.id()))
                .map(LocationView::locationType)
                .toList());
        List<String> codes = locations.stream().map(LocationView::code).toList();
        List<String> sortedCodes = codes.stream().sorted().toList();
        assertEquals(sortedCodes, codes);
    }
}
