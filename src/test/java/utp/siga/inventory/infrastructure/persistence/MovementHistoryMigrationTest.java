package utp.siga.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MovementHistoryMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_v8_migration_test")
            .withUsername("siga_inventory_v8_migration_test")
            .withPassword("v8-migration-test-only");

    @BeforeEach
    void reset() throws SQLException {
        execute("DROP SCHEMA IF EXISTS inventory CASCADE");
    }

    @Test
    void freshInstallAppliesV1ThroughV8() throws SQLException {
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(8);
        assertV8Indexes();
    }

    @Test
    void upgradeV7ToV8PreservesMovementsAndDetails() throws SQLException {
        assertThat(flyway(MigrationVersion.fromVersion("7")).migrate().migrationsExecuted).isEqualTo(7);
        long movements = count("SELECT count(*) FROM inventory.movement");
        long details = count("SELECT count(*) FROM inventory.movement_detail");

        assertThat(flyway(null).migrate().migrationsExecuted).isOne();

        assertV8Indexes();
        assertThat(count("SELECT count(*) FROM inventory.movement")).isEqualTo(movements);
        assertThat(count("SELECT count(*) FROM inventory.movement_detail")).isEqualTo(details);
    }

    private void assertV8Indexes() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='inventory' AND indexname IN "
                + "('idx_movement_history_created','idx_movement_requested_created',"
                + "'idx_movement_detail_product_movement','idx_movement_detail_lot_movement',"
                + "'idx_movement_detail_asset_movement','idx_movement_detail_origin_movement',"
                + "'idx_movement_detail_destination_movement')")).isEqualTo(7);
    }

    private Flyway flyway(MigrationVersion target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).defaultSchema("inventory").schemas("inventory").createSchemas(true);
        if (target != null) config.target(target);
        return config.load();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
