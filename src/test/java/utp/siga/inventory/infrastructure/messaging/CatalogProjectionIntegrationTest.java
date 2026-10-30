package utp.siga.inventory.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import utp.siga.inventory.InventoryIntegrationEnvironment;
import utp.siga.inventory.application.query.GetMovementQuery;
import utp.siga.inventory.application.query.GetStockQuery;
import utp.siga.inventory.application.usecase.SyncCatalogProjectionUseCase;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.UnitConversionRef;

@SpringBootTest
class CatalogProjectionIntegrationTest extends InventoryIntegrationEnvironment {
    private static final String EXCHANGE = "siga.review.events";

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SyncCatalogProjectionUseCase sync;
    @Autowired GetStockQuery stockQuery;
    @Autowired GetMovementQuery movementQuery;

    @Test
    void migrationsAndImplementedQueriesMatchTheDedicatedDatabase() {
        assertEquals("siga_inventory_review_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("siga_inventory_review_test", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(7L, scalar("SELECT count(*) FROM inventory.flyway_schema_history WHERE success"));

        UUID productId = UUID.fromString("22000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        var stock = stockQuery.getStock(productId, locationId, null, null, 0, 20);
        assertEquals(1, stock.content().size());
        assertEquals(new BigDecimal("2500.000000"), stock.content().getFirst().quantity());

        UUID movementId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.movement(id,movement_code,movement_type,status,registered_by_user_id,is_sensitive)
                VALUES (? ,?,'ENTRY','CONFIRMED',?,false)
                """, movementId, "MOV-REVIEW-" + movementId, actorId);
        jdbc.update("""
                INSERT INTO inventory.movement_detail(id,movement_id,line_number,product_id,entered_quantity,unit_code,
                    conversion_factor,stock_quantity,destination_location_id,original_currency)
                VALUES (?,?,1,?,2,'T',1000,2000,?,'PEN')
                """, detailId, movementId, productId, locationId);

        var movement = movementQuery.getMovement(movementId).orElseThrow();
        assertEquals("ENTRY", movement.movementType());
        assertEquals("CONFIRMED", movement.status());
        assertEquals(actorId, movement.registeredByUserId());
        assertEquals(1, movement.details().size());
        assertEquals(new BigDecimal("2000.000000"), movement.details().getFirst().stockQuantity());
    }

    @Test
    void consumesAllFiveCanonicalEventsAndReplacesConversions() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ObjectNode created = productEvent("ProductCreated", productId, 0, true,
                List.of(conversion("BOX", "EA", "10.000000", true), conversion("PAL", "EA", "100.000000", true)));
        ObjectNode updated = productEvent("ProductUpdated", productId, 1, false,
                List.of(conversion("CASE", "EA", "12.000000", true)));
        ObjectNode supplierCreated = supplierEvent("SupplierCreated", supplierId, 0, true);
        ObjectNode supplierUpdated = supplierEvent("SupplierUpdated", supplierId, 1, true);
        ObjectNode supplierDisabled = supplierEvent("SupplierDisabled", supplierId, 2, false);

        publish("catalog.product.created.v1", created);
        publish("catalog.product.updated.v1", updated);
        publish("catalog.supplier.created.v1", supplierCreated);
        publish("catalog.supplier.updated.v1", supplierUpdated);
        publish("catalog.supplier.disabled.v1", supplierDisabled);

        awaitCount("SELECT count(*) FROM inventory.processed_event WHERE event_id IN (?,?,?,?,?)", 5,
                uuid(created, "eventId"), uuid(updated, "eventId"), uuid(supplierCreated, "eventId"),
                uuid(supplierUpdated, "eventId"), uuid(supplierDisabled, "eventId"));
        assertEquals(1L, scalar("SELECT source_version FROM inventory.product_ref WHERE product_id=?", productId));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM inventory.product_ref WHERE product_id=?", Boolean.class, productId)));
        assertEquals(List.of("CASE->EA"), jdbc.query(
                "SELECT from_unit_code || '->' || to_unit_code FROM inventory.product_unit_conversion_ref WHERE product_id=? ORDER BY 1",
                (rs, row) -> rs.getString(1), productId));
        assertEquals(2L, scalar("SELECT source_version FROM inventory.supplier_ref WHERE supplier_id=?", supplierId));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM inventory.supplier_ref WHERE supplier_id=?", Boolean.class, supplierId)));
    }

    @Test
    void concurrentDuplicateIsClaimedAndAppliedExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductRef product = productRef(productId, "CONCURRENT-" + productId, 1,
                List.of(new UnitConversionRef("BOX", "EA", new BigDecimal("10.000000"), true)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> runTogether(ready, start, eventId, product));
            Future<?> second = executor.submit(() -> runTogether(ready, start, eventId, product));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1L, scalar("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", eventId));
        assertEquals(1L, scalar("SELECT count(*) FROM inventory.product_ref WHERE product_id=?", productId));
        assertEquals(1L, scalar("SELECT count(*) FROM inventory.product_unit_conversion_ref WHERE product_id=?", productId));
    }

    @Test
    void olderEventCannotRegressProductOrDeleteConversions() throws Exception {
        UUID productId = UUID.randomUUID();
        ObjectNode newer = productEvent("ProductUpdated", productId, 3, true,
                List.of(conversion("BOX", "EA", "20.000000", true)));
        ObjectNode older = productEvent("ProductUpdated", productId, 2, false, List.of());
        publish("catalog.product.updated.v1", newer);
        awaitCount("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", 1, uuid(newer, "eventId"));
        publish("catalog.product.updated.v1", older);
        awaitCount("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", 1, uuid(older, "eventId"));

        assertEquals(3L, scalar("SELECT source_version FROM inventory.product_ref WHERE product_id=?", productId));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM inventory.product_ref WHERE product_id=?", Boolean.class, productId)));
        assertEquals(1L, scalar("SELECT count(*) FROM inventory.product_unit_conversion_ref WHERE product_id=?", productId));
    }

    @Test
    void projectionFailureRollsBackClaimAndProduct() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductRef invalidForDatabase = productRef(productId, "ROLLBACK-" + productId, 1,
                List.of(new UnitConversionRef("BOX", "EA", new BigDecimal("1000000000000.000000"), true)));

        assertThrows(DataAccessException.class, () -> sync.syncProduct(eventId, invalidForDatabase));
        assertEquals(0L, scalar("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", eventId));
        assertEquals(0L, scalar("SELECT count(*) FROM inventory.product_ref WHERE product_id=?", productId));
    }

    @Test
    void failedMessageIsDeadLetteredAndSameEventCanRecover() throws Exception {
        UUID conflictId = UUID.randomUUID();
        String sku = "RETRY-" + conflictId;
        jdbc.update("""
                INSERT INTO inventory.product_ref(product_id,sku,product_type,storage_unit_code,base_unit_code,min_stock,
                    requires_lot,requires_heat_number,requires_serial,requires_expiry,active,source_version)
                VALUES (? ,?,'MATERIAL','EA','EA',0,false,false,false,false,true,0)
                """, conflictId, sku);
        UUID targetId = UUID.randomUUID();
        ObjectNode event = productEvent("ProductUpdated", targetId, 1, true, List.of());
        event.withObject("/payload/product").put("sku", sku);
        assertCanonical("ProductUpdated", event);

        rabbit.convertAndSend(EXCHANGE, "catalog.product.updated.v1", json.writeValueAsBytes(event));
        Message dead = rabbit.receive(QUEUE + ".dlq", 10_000);
        assertNotNull(dead);
        UUID eventId = uuid(event, "eventId");
        assertEquals(0L, scalar("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", eventId));
        assertEquals(0L, scalar("SELECT count(*) FROM inventory.product_ref WHERE product_id=?", targetId));

        jdbc.update("DELETE FROM inventory.product_ref WHERE product_id=?", conflictId);
        rabbit.convertAndSend(EXCHANGE, "catalog.product.updated.v1", dead.getBody());
        awaitCount("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", 1, eventId);
        assertEquals(1L, scalar("SELECT count(*) FROM inventory.product_ref WHERE product_id=?", targetId));
    }

    @Test
    void incompleteUpdatedMessageIsRejectedToDedicatedDeadLetterQueue() throws Exception {
        ObjectNode event = productEvent("ProductUpdated", UUID.randomUUID(), 1, true, List.of());
        event.withObject("/payload/product").remove("name");
        rabbit.convertAndSend(EXCHANGE, "catalog.product.updated.v1", json.writeValueAsBytes(event));

        Message dead = rabbit.receive(QUEUE + ".dlq", 10_000);
        assertNotNull(dead);
        assertEquals(0L, scalar("SELECT count(*) FROM inventory.processed_event WHERE event_id=?", uuid(event, "eventId")));
    }

    private void runTogether(CountDownLatch ready, CountDownLatch start, UUID eventId, ProductRef product) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
            sync.syncProduct(eventId, product);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void publish(String route, ObjectNode event) throws Exception {
        assertCanonical(event.path("eventType").asText(), event);
        rabbit.convertAndSend(EXCHANGE, route, json.writeValueAsBytes(event));
    }

    private void assertCanonical(String eventType, JsonNode event) throws Exception {
        String file = switch (eventType) {
            case "ProductCreated" -> "product-created-v1.schema.json";
            case "ProductUpdated" -> "product-updated-v1.schema.json";
            case "SupplierCreated" -> "supplier-created-v1.schema.json";
            case "SupplierUpdated" -> "supplier-updated-v1.schema.json";
            case "SupplierDisabled" -> "supplier-disabled-v1.schema.json";
            default -> throw new IllegalArgumentException(eventType);
        };
        Path path = Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2", "api", file);
        JsonNode schema = json.readTree(Files.readString(path));
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        assertTrue(validator.validate(event).isEmpty(), () -> validator.validate(event).toString());
    }

    private ObjectNode productEvent(String type, UUID productId, long version, boolean active,
            List<ObjectNode> conversions) {
        ObjectNode event = envelope(type, "Product", productId);
        ObjectNode payload = event.putObject("payload");
        ObjectNode product = payload.putObject("product");
        product.put("id", productId.toString());
        product.put("sku", "SKU-" + productId);
        if (type.equals("ProductUpdated")) {
            product.put("name", "Reviewed product");
            product.put("categoryId", UUID.randomUUID().toString());
            product.put("categoryCode", "REVIEW");
            product.put("categoryName", "Review category");
        }
        product.put("productType", "MATERIAL");
        product.put("storageUnitCode", "BOX");
        product.put("baseUnitCode", "EA");
        product.put("minStock", new BigDecimal("1.000000"));
        product.put("requiresLot", true);
        product.put("requiresHeatNumber", false);
        product.put("requiresExpiry", false);
        product.put("requiresSerial", false);
        product.put("active", active);
        product.put("version", version);
        ArrayNode array = payload.putArray("conversions");
        conversions.forEach(array::add);
        return event;
    }

    private ObjectNode supplierEvent(String type, UUID supplierId, long version, boolean active) {
        ObjectNode event = envelope(type, "Supplier", supplierId);
        ObjectNode supplier = event.putObject("payload").putObject("supplier");
        supplier.put("id", supplierId.toString());
        supplier.put("code", "SUP-" + supplierId);
        supplier.put("taxId", supplierId.toString().replace("-", "").substring(0, 11));
        supplier.put("businessName", "Review supplier " + version);
        supplier.put("active", active);
        supplier.put("version", version);
        return event;
    }

    private ObjectNode envelope(String type, String aggregateType, UUID aggregateId) {
        ObjectNode event = json.createObjectNode();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", type);
        event.put("schemaVersion", 1);
        event.put("aggregateType", aggregateType);
        event.put("aggregateId", aggregateId.toString());
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("producer", "catalog-service");
        return event;
    }

    private ObjectNode conversion(String from, String to, String factor, boolean active) {
        ObjectNode conversion = json.createObjectNode();
        conversion.put("fromUnitCode", from);
        conversion.put("toUnitCode", to);
        conversion.put("factor", new BigDecimal(factor));
        conversion.put("active", active);
        return conversion;
    }

    private ProductRef productRef(UUID id, String sku, long version, List<UnitConversionRef> conversions) {
        return new ProductRef(id, sku, "MATERIAL", "BOX", "EA", BigDecimal.ONE,
                true, false, false, false, true, version, Instant.now(), conversions);
    }

    private UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private long scalar(String sql, Object... args) {
        Number result = jdbc.queryForObject(sql, Number.class, args);
        return result == null ? 0 : result.longValue();
    }

    private void awaitCount(String sql, long expected, Object... args) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (scalar(sql, args) == expected) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, scalar(sql, args));
    }
}
