package utp.siga.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import utp.siga.inventory.InventoryIntegrationEnvironment;
import utp.siga.inventory.application.dto.LotStatus;
import utp.siga.inventory.application.exception.LotConflictException;
import utp.siga.inventory.application.port.in.LotAdminUseCase;
import utp.siga.inventory.application.port.out.LotStore;
import utp.siga.inventory.application.query.ListLotsQuery;
import utp.siga.inventory.infrastructure.messaging.InventoryOutboxPublisher;

@SpringBootTest
class PostgresLotStoreIntegrationTest extends InventoryIntegrationEnvironment {
    private static final LocalDate FIXED_DATE = LocalDate.of(2026, 10, 28);
    @Autowired LotAdminUseCase lots;
    @Autowired LotStore store;
    @Autowired ListLotsQuery operational;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired InventoryOutboxPublisher publisher;
    @Autowired AmqpAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbit;
    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> lotIds = new ArrayList<>();

    @BeforeEach void product() { createProduct(false, false, false, true); }

    @AfterEach
    void cleanup() {
        for (UUID id : lotIds) {
            jdbc.update("DELETE FROM inventory.outbox_event WHERE aggregate_id=?", id);
            jdbc.update("DELETE FROM inventory.stock_balance WHERE lot_id=?", id);
            jdbc.update("DELETE FROM inventory.lot WHERE id=?", id);
        }
        for (UUID id : productIds) {
            jdbc.update("DELETE FROM inventory.product_unit_conversion_ref WHERE product_id=?", id);
            jdbc.update("DELETE FROM inventory.product_ref WHERE product_id=?", id);
        }
    }

    @Test
    void createsNullableIdentityAndCanonicalEvent() throws Exception {
        var created = create(null, null, null);
        assertThat(created.lotNumber()).isNull();
        assertThat(created.status()).isEqualTo(LotStatus.ACTIVE);
        assertThat(created.version()).isZero();

        JsonNode envelope = event(created.id(), "CREATED");
        assertThat(envelope.path("payload").path("before").isNull()).isTrue();
        assertThat(envelope.path("payload").path("after").path("lotNumber").isNull()).isTrue();
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                mapper.readTree(Files.readString(Path.of("..", "siga-documentation",
                        "SIGA_Documentacion_Tecnica_Final_v1.2", "api", "lot-changed-v1.schema.json"))));
        assertThat(schema.validate(envelope)).isEmpty();
    }

    @Test
    void validatesTrackingFlagsDuplicateIdentityAndDateRelationWithoutRejectedEvents() {
        UUID tracked = createProduct(true, true, true, true);
        assertThatThrownBy(() -> lots.create(new LotAdminUseCase.Create(tracked, null, "H", null, null,
                FIXED_DATE.plusDays(1), actor(), correlation()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lots.create(new LotAdminUseCase.Create(tracked, "L", null, null, null,
                FIXED_DATE.plusDays(1), actor(), correlation()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lots.create(new LotAdminUseCase.Create(tracked, "L", "H", null, null,
                null, actor(), correlation()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lots.create(new LotAdminUseCase.Create(tracked, "L", "H",
                FIXED_DATE, null, FIXED_DATE.minusDays(1), actor(), correlation())))
                .isInstanceOf(IllegalArgumentException.class);

        var first = create("UNIQUE", null, null);
        assertThatThrownBy(() -> create("UNIQUE", null, null))
                .isInstanceOf(LotConflictException.class).extracting("code").isEqualTo("DATA_CONFLICT");
        assertThat(eventCount(first.id())).isOne();
    }

    @Test
    void appliesUtcExpirationBoundaryAndCorrectsPersistedStatusOnUpdate() {
        var yesterday = createFixed("YESTERDAY", FIXED_DATE.minusDays(1));
        var today = createFixed("TODAY", FIXED_DATE);
        var future = createFixed("FUTURE", FIXED_DATE.plusDays(1));
        assertThat(yesterday.status()).isEqualTo(LotStatus.EXPIRED);
        assertThat(today.status()).isEqualTo(LotStatus.ACTIVE);
        assertThat(future.status()).isEqualTo(LotStatus.ACTIVE);

        var corrected = store.update(new LotAdminUseCase.Update(yesterday.id(), null, null,
                FIXED_DATE.plusDays(2), 0, actor(), correlation()), FIXED_DATE);
        assertThat(corrected.status()).isEqualTo(LotStatus.ACTIVE);
        var expired = store.update(new LotAdminUseCase.Update(future.id(), null, null,
                FIXED_DATE.minusDays(2), 0, actor(), correlation()), FIXED_DATE);
        assertThat(expired.status()).isEqualTo(LotStatus.EXPIRED);
    }

    @Test
    void checksVersionBeforeSemanticNoOpAndWritesNoEventForNoOpOrConflict() {
        var created = create("NOOP", FIXED_DATE.minusDays(4), FIXED_DATE.plusDays(4));
        long events = eventCount(created.id());
        var noOp = lots.update(new LotAdminUseCase.Update(created.id(), created.manufacturedAt(),
                created.receivedAt(), created.expiresAt(), 0, actor(), correlation()));
        assertThat(noOp.version()).isZero();
        assertThat(noOp.updatedAt()).isEqualTo(created.updatedAt());
        assertThat(eventCount(created.id())).isEqualTo(events);
        assertThatThrownBy(() -> lots.update(new LotAdminUseCase.Update(created.id(), created.manufacturedAt(),
                created.receivedAt(), created.expiresAt(), 9, actor(), correlation())))
                .isInstanceOf(LotConflictException.class).extracting("code").isEqualTo("VERSION_CONFLICT");
        assertThat(eventCount(created.id())).isEqualTo(events);
    }

    @Test
    void supportsAdminListingDetailFiltersQuantitiesAndEveryState() {
        var active = create("LIST-A", null, null);
        var blocked = status(active, LotStatus.BLOCKED);
        var expired = createFixed("LIST-E", FIXED_DATE.minusDays(1));
        UUID location = UUID.fromString("30000000-0000-0000-0000-000000000003");
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,lot_id,quantity,avg_cost_pen) "
                + "VALUES (?,?,?,?,0)", active.productId(), location, blocked.id(), new BigDecimal("3.5"));

        var page = lots.list(new LotAdminUseCase.Filter(active.productId(), null, null, null, 0, 20));
        assertThat(page.content()).extracting("id").contains(blocked.id(), expired.id());
        assertThat(lots.list(new LotAdminUseCase.Filter(null, LotStatus.BLOCKED, null, null, 0, 20)).content())
                .extracting("id").contains(blocked.id());
        assertThat(lots.list(new LotAdminUseCase.Filter(null, null, true, null, 0, 20)).content())
                .extracting("id").contains(expired.id());
        assertThat(lots.list(new LotAdminUseCase.Filter(null, null, null, location, 0, 20)).content())
                .extracting("id").contains(blocked.id());
        assertThat(lots.get(blocked.id()).totalQuantity()).isEqualByComparingTo("3.5");
        assertThat(lots.get(blocked.id()).locations()).singleElement()
                .satisfies(q -> assertThat(q.locationId()).isEqualTo(location));
    }

    @Test
    void enforcesCanonicalManualLifecycleAndZeroStockDepletedReopening() {
        var first = create("LIFE-A", null, null);
        var quarantined = status(first, LotStatus.QUARANTINED);
        var blocked = status(quarantined, LotStatus.BLOCKED);
        var backToQuarantine = status(blocked, LotStatus.QUARANTINED);
        assertThat(status(backToQuarantine, LotStatus.ACTIVE).status()).isEqualTo(LotStatus.ACTIVE);

        var second = create("LIFE-B", null, null);
        assertThat(status(second, LotStatus.BLOCKED).status()).isEqualTo(LotStatus.BLOCKED);
        assertThatThrownBy(() -> lots.changeStatus(new LotAdminUseCase.ChangeStatus(second.id(),
                LotStatus.EXPIRED, 0, actor(), correlation()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lots.changeStatus(new LotAdminUseCase.ChangeStatus(second.id(),
                LotStatus.DEPLETED, 0, actor(), correlation()))).isInstanceOf(IllegalArgumentException.class);

        jdbc.update("UPDATE inventory.lot SET status='DEPLETED' WHERE id=?", second.id());
        assertThat(status(lots.get(second.id()), LotStatus.ACTIVE).status()).isEqualTo(LotStatus.ACTIVE);
    }

    @Test
    void blocksInvalidReactivationAndPositiveStockDepletedReopening() {
        var expired = createFixed("EXPIRED-BLOCK", FIXED_DATE.minusDays(1));
        assertThatThrownBy(() -> store.changeStatus(new LotAdminUseCase.ChangeStatus(expired.id(), LotStatus.ACTIVE,
                0, actor(), correlation()), FIXED_DATE)).isInstanceOf(LotConflictException.class)
                .extracting("code").isEqualTo("LOT_STATUS_CONFLICT");

        var lot = create("DEPLETED-STOCK", null, null);
        UUID location = UUID.fromString("30000000-0000-0000-0000-000000000003");
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,lot_id,quantity,avg_cost_pen) "
                + "VALUES (?,?,?,?,0)", lot.productId(), location, lot.id(), BigDecimal.ONE);
        jdbc.update("UPDATE inventory.lot SET status='DEPLETED' WHERE id=?", lot.id());
        assertThatThrownBy(() -> lots.changeStatus(new LotAdminUseCase.ChangeStatus(lot.id(), LotStatus.ACTIVE,
                0, actor(), correlation()))).isInstanceOf(LotConflictException.class)
                .extracting("code").isEqualTo("LOT_STATUS_CONFLICT");
    }

    @Test
    void operationalSelectorKeepsOnlyEffectiveActiveLots() {
        var active = create("SELECT-A", null, null);
        var blocked = status(create("SELECT-B", null, null), LotStatus.BLOCKED);
        var expired = createFixed("SELECT-E", LocalDate.now(ZoneOffset.UTC).minusDays(1));
        UUID location = UUID.fromString("30000000-0000-0000-0000-000000000003");
        for (var lot : List.of(active, blocked, expired)) {
            jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,lot_id,quantity,avg_cost_pen) "
                    + "VALUES (?,?,?,?,0)", lot.productId(), location, lot.id(), BigDecimal.ONE);
        }
        assertThat(operational.listLots(active.productId(), location, true, 0, 20).content())
                .extracting("id").contains(active.id()).doesNotContain(blocked.id(), expired.id());
    }

    @Test
    void concurrentUpdatesWithSameVersionCannotLoseChanges() throws Exception {
        var lot = create("CONCURRENT", null, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> first = executor.submit(() -> updateOutcome(start, lot.id(), FIXED_DATE.plusDays(1)));
            Future<Object> second = executor.submit(() -> updateOutcome(start, lot.id(), FIXED_DATE.plusDays(2)));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .filteredOn(value -> value instanceof LotConflictException).singleElement()
                    .extracting(value -> ((LotConflictException) value).code()).isEqualTo("VERSION_CONFLICT");
        }
        assertThat(lots.get(lot.id()).version()).isOne();
        assertThat(eventCount(lot.id())).isEqualTo(2);
    }

    @Test
    void publisherRoutesPersistentLotChangedMessage() throws Exception {
        String queueName = "inventory.lot.changed.test." + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, false, true);
        TopicExchange exchange = new TopicExchange("siga.events", true, false);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("inventory.lot.changed.v1"));
        try {
            var lot = create("PUBLISH", null, null);
            assertThat(publisher.publishPending()).isPositive();
            Message message = receiveFor(queueName, lot.id());
            assertThat(message).isNotNull();
            assertThat(message.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
            assertThat(mapper.readTree(message.getBody()).path("aggregateId").asText())
                    .isEqualTo(lot.id().toString());
            assertThat(jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE aggregate_id=?",
                    String.class, lot.id())).isEqualTo("PUBLISHED");
        } finally { rabbitAdmin.deleteQueue(queueName); }
    }

    private UUID createProduct(boolean lot, boolean heat, boolean expiry, boolean active) {
        UUID id = UUID.randomUUID(); productIds.add(id);
        jdbc.update("INSERT INTO inventory.product_ref(product_id,sku,product_type,storage_unit_code,base_unit_code,"
                + "requires_lot,requires_heat_number,requires_expiry,active) VALUES (?,?, 'MATERIAL','EA','EA',?,?,?,?)",
                id, "LOT-TEST-" + id, lot, heat, expiry, active);
        return id;
    }
    private utp.siga.inventory.application.dto.LotAdminView create(String number, LocalDate expiry) {
        return create(number, null, expiry);
    }
    private utp.siga.inventory.application.dto.LotAdminView create(String number, LocalDate manufactured, LocalDate expiry) {
        var result = lots.create(new LotAdminUseCase.Create(productIds.getFirst(), number, null, manufactured,
                null, expiry, actor(), correlation())); lotIds.add(result.id()); return result;
    }
    private utp.siga.inventory.application.dto.LotAdminView createFixed(String number, LocalDate expiry) {
        var result = store.create(new LotAdminUseCase.Create(productIds.getFirst(), number, null, null,
                null, expiry, actor(), correlation()), FIXED_DATE); lotIds.add(result.id()); return result;
    }
    private utp.siga.inventory.application.dto.LotAdminView status(
            utp.siga.inventory.application.dto.LotAdminView lot, LotStatus status) {
        return lots.changeStatus(new LotAdminUseCase.ChangeStatus(lot.id(), status, lot.version(), actor(), correlation()));
    }
    private JsonNode event(UUID id, String type) throws Exception {
        String value = jdbc.queryForObject("SELECT payload::text FROM inventory.outbox_event WHERE aggregate_id=? "
                + "AND payload->'payload'->>'changeType'=? ORDER BY occurred_at DESC LIMIT 1", String.class, id, type);
        return mapper.readTree(value);
    }
    private long eventCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", Long.class, id);
    }
    private Object updateOutcome(CountDownLatch start, UUID id, LocalDate expiry) throws InterruptedException {
        start.await();
        try {
            return lots.update(new LotAdminUseCase.Update(id, null, null, expiry,
                    0, actor(), correlation()));
        } catch (LotConflictException conflict) { return conflict; }
    }
    private Message receiveFor(String queueName, UUID aggregateId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Message message = rabbit.receive(queueName, 250);
            if (message != null && aggregateId.toString().equals(
                    mapper.readTree(message.getBody()).path("aggregateId").asText())) return message;
        }
        return null;
    }
    private static UUID actor() { return UUID.randomUUID(); }
    private static UUID correlation() { return UUID.randomUUID(); }
}
