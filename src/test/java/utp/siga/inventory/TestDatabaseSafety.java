package utp.siga.inventory;

import java.util.regex.Pattern;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

public class TestDatabaseSafety implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Pattern SAFE_DATABASE = Pattern.compile("siga_inventory_[a-z0-9_]+_test");
    private static final Pattern SAFE_USER = Pattern.compile("siga_inventory_(?:test|[a-z0-9_]+_test)");

    public void initialize(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(factory -> validate(context.getEnvironment()));
    }

    static void validate(Environment env) {
        String expectedDatabase = env.getProperty("inventory.test.expected-database", "siga_inventory_local_test");
        String expectedUser = env.getProperty("inventory.test.expected-user", "siga_inventory_test");
        String url = env.getProperty("spring.datasource.url", "");
        if (!SAFE_DATABASE.matcher(expectedDatabase).matches()
                || !SAFE_USER.matcher(expectedUser).matches()
                || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/" + Pattern.quote(expectedDatabase))
                || !expectedUser.equals(env.getProperty("spring.datasource.username")))
            throw new IllegalStateException("TEST_DATABASE_GUARD: dedicated Inventory test database/login required");

        for (String key : new String[]{"spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.datasource.jndi-name", "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
                "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-properties.url", "spring.datasource.hikari.data-source-properties.user",
                "spring.datasource.hikari.data-source-properties.databaseName", "spring.datasource.type"})
            if (env.containsProperty(key))
                throw new IllegalStateException("TEST_DATABASE_GUARD: alternate connection forbidden: " + key);

        if (!"inventory".equals(env.getProperty("spring.flyway.schemas"))
                || !"inventory".equals(env.getProperty("spring.flyway.default-schema")))
            throw new IllegalStateException("TEST_DATABASE_GUARD: Inventory schema required");
    }
}
