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
class LotAssetAdminMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_v7_migration_test")
            .withUsername("siga_inventory_v7_migration_test")
            .withPassword("v7-migration-test-only");

    @BeforeEach void reset() throws SQLException { execute("DROP SCHEMA IF EXISTS inventory CASCADE"); }

    @Test
    void freshInstallAppliesV1ThroughV7() throws SQLException {
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(7);
        assertV7Shape();
    }

    @Test
    void upgradeV6ToV7PreservesLotsAssetsStockAndMovements() throws SQLException {
        assertThat(flyway(MigrationVersion.fromVersion("6")).migrate().migrationsExecuted).isEqualTo(6);
        long lots = count("SELECT count(*) FROM inventory.lot");
        long assets = count("SELECT count(*) FROM inventory.asset");
        long stock = count("SELECT count(*) FROM inventory.stock_balance");
        long movements = count("SELECT count(*) FROM inventory.movement");

        assertThat(flyway(null).migrate().migrationsExecuted).isOne();

        assertV7Shape();
        assertThat(count("SELECT count(*) FROM inventory.lot")).isEqualTo(lots);
        assertThat(count("SELECT count(*) FROM inventory.asset")).isEqualTo(assets);
        assertThat(count("SELECT count(*) FROM inventory.stock_balance")).isEqualTo(stock);
        assertThat(count("SELECT count(*) FROM inventory.movement")).isEqualTo(movements);
        assertThat(count("SELECT count(*) FROM inventory.lot WHERE version=0")).isEqualTo(lots);
        assertThat(count("SELECT count(*) FROM inventory.asset WHERE version=0")).isEqualTo(assets);
    }

    private void assertV7Shape() throws SQLException {
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='inventory' "
                + "AND table_name IN ('lot','asset') AND column_name='version' AND is_nullable='NO'"))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='inventory' AND indexname IN "
                + "('idx_lot_admin_status_created','idx_asset_admin_status_created',"
                + "'idx_lot_admin_product_created','idx_asset_admin_product_created')")).isEqualTo(4);
    }

    private Flyway flyway(MigrationVersion target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).defaultSchema("inventory").schemas("inventory").createSchemas(true);
        if (target != null) config.target(target);
        return config.load();
    }
    private void execute(String sql) throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement()) { s.execute(sql); }
    }
    private long count(String sql) throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next(); return r.getLong(1);
        }
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
