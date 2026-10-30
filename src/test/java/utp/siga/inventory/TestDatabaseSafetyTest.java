package utp.siga.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseSafetyTest {
    @Test
    void acceptsValidConfiguration() {
        var env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/siga_inventory_local_test");
        env.setProperty("spring.datasource.username", "siga_inventory_test");
        env.setProperty("spring.flyway.schemas", "inventory");
        env.setProperty("spring.flyway.default-schema", "inventory");
        assertDoesNotThrow(() -> TestDatabaseSafety.validate(env));
    }

    @Test
    void rejectsInvalidUrl() {
        var env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/siga_inventory_local_test");
        env.setProperty("spring.datasource.username", "siga_inventory_test");
        env.setProperty("spring.flyway.schemas", "inventory");
        env.setProperty("spring.flyway.default-schema", "inventory");
        assertThrows(IllegalStateException.class, () -> TestDatabaseSafety.validate(env));
    }

    @Test
    void acceptsExplicitDedicatedReviewDatabaseAndLogin() {
        var env = validEnvironment();
        env.setProperty("inventory.test.expected-database", "siga_inventory_review_test");
        env.setProperty("inventory.test.expected-user", "siga_inventory_review_test");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55432/siga_inventory_review_test");
        env.setProperty("spring.datasource.username", "siga_inventory_review_test");
        assertDoesNotThrow(() -> TestDatabaseSafety.validate(env));
    }

    @Test
    void rejectsOperationalDatabaseEvenWhenDeclaredAsExpected() {
        var env = validEnvironment();
        env.setProperty("inventory.test.expected-database", "siga");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/siga");
        assertThrows(IllegalStateException.class, () -> TestDatabaseSafety.validate(env));
    }

    @Test
    void rejectsMismatchedLoginAndAlternateConnectionProperties() {
        var mismatched = validEnvironment();
        mismatched.setProperty("spring.datasource.username", "siga");
        assertThrows(IllegalStateException.class, () -> TestDatabaseSafety.validate(mismatched));

        var alternate = validEnvironment();
        alternate.setProperty("spring.flyway.url", "jdbc:postgresql://127.0.0.1:5432/other");
        assertThrows(IllegalStateException.class, () -> TestDatabaseSafety.validate(alternate));
    }

    private MockEnvironment validEnvironment() {
        var env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/siga_inventory_local_test");
        env.setProperty("spring.datasource.username", "siga_inventory_test");
        env.setProperty("spring.flyway.schemas", "inventory");
        env.setProperty("spring.flyway.default-schema", "inventory");
        return env;
    }
}
