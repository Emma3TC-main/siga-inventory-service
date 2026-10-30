package utp.siga.inventory;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

public abstract class InventoryIntegrationEnvironment {
    protected static final String QUEUE = "inventory.catalog.review.events";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_review_test")
            .withUsername("siga_inventory_review_test")
            .withPassword("inventory-review-only")
            .withInitScript("db/init-review.sql");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine");

    static {
        Startables.deepStart(POSTGRES, RABBIT).join();
    }

    @DynamicPropertySource
    static void isolatedInfrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("inventory.test.expected-database", POSTGRES::getDatabaseName);
        properties.add("inventory.test.expected-user", POSTGRES::getUsername);
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        properties.add("inventory.catalog.consumer.enabled", () -> "true");
        properties.add("inventory.catalog.consumer.exchange", () -> "siga.review.events");
        properties.add("inventory.catalog.consumer.queue", () -> QUEUE);
        properties.add("inventory.catalog.consumer.retry.max-attempts", () -> "3");
        properties.add("inventory.catalog.consumer.retry.initial-interval-ms", () -> "10");
        properties.add("inventory.catalog.consumer.retry.multiplier", () -> "1.1");
        properties.add("inventory.catalog.consumer.retry.max-interval-ms", () -> "10");
        properties.add("inventory.security.jwks-uri", () -> "http://127.0.0.1:1/.well-known/jwks.json");
        properties.add("inventory.security.issuer", () -> "siga-identity");
        properties.add("inventory.security.audience", () -> "siga-api");
        properties.add("spring.task.scheduling.enabled", () -> "false");
        properties.add("inventory.outbox.publisher.initial-delay-ms", () -> "86400000");
        properties.add("inventory.outbox.publisher.fixed-delay-ms", () -> "86400000");
    }
}
