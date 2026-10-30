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
class PhysicalCountMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_count_migration_test")
            .withUsername("siga_inventory_count_migration_test")
            .withPassword("count-migration-test-only");

    @BeforeEach
    void reset() throws SQLException {
        execute("DROP SCHEMA IF EXISTS inventory CASCADE");
    }

    @Test
    void freshInstallAppliesV1ThroughV6() throws SQLException {
        assertThat(flyway(MigrationVersion.fromVersion("6")).migrate().migrationsExecuted).isEqualTo(6);
        assertV5Shape();
        assertV6Indexes();
    }

    @Test
    void upgradeFromV4PreservesExistingCountAndAddsMvpShape() throws SQLException {
        assertThat(flyway(MigrationVersion.fromVersion("4")).migrate().migrationsExecuted).isEqualTo(4);
        execute("INSERT INTO inventory.physical_count(id,location_id,counted_by_user_id,notes) VALUES "
                + "('44000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000003',"
                + "'12000000-0000-0000-0000-000000000001','preserve')");

        assertThat(flyway(MigrationVersion.fromVersion("6")).migrate().migrationsExecuted).isEqualTo(2);

        assertV5Shape();
        assertV6Indexes();
        assertThat(count("SELECT count(*) FROM inventory.physical_count WHERE "
                + "id='44000000-0000-0000-0000-000000000001' AND created_by_user_id="
                + "'12000000-0000-0000-0000-000000000001' AND notes='preserve' AND version=0"))
                .isOne();
    }

    @Test
    void upgradeFromV5ToV6PreservesLocationsStockAndCounts() throws SQLException {
        assertThat(flyway(MigrationVersion.fromVersion("5")).migrate().migrationsExecuted).isEqualTo(5);
        execute("INSERT INTO inventory.physical_count(id,location_id,status,created_by_user_id) VALUES "
                + "('44000000-0000-0000-0000-000000000009','30000000-0000-0000-0000-000000000003',"
                + "'DRAFT','12000000-0000-0000-0000-000000000001')");
        long locations = count("SELECT count(*) FROM inventory.location");
        long stock = count("SELECT count(*) FROM inventory.stock_balance");
        assertThat(flyway(MigrationVersion.fromVersion("6")).migrate().migrationsExecuted).isOne();
        assertV6Indexes();
        assertThat(count("SELECT count(*) FROM inventory.location")).isEqualTo(locations);
        assertThat(count("SELECT count(*) FROM inventory.stock_balance")).isEqualTo(stock);
        assertThat(count("SELECT count(*) FROM inventory.physical_count WHERE id="
                + "'44000000-0000-0000-0000-000000000009'")).isOne();
    }

    private void assertV6Indexes() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='inventory' AND indexname IN "
                + "('idx_stock_location_positive','idx_physical_count_location_open')")).isEqualTo(2);
    }

    private void assertV5Shape() throws SQLException {
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='inventory' "
                + "AND table_name='physical_count' AND column_name IN "
                + "('created_by_user_id','completed_by_user_id','cancelled_by_user_id','cancelled_at',"
                + "'cancellation_reason','version','updated_at')")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='inventory' "
                + "AND indexname='uq_physical_count_dimension'")).isOne();
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
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
