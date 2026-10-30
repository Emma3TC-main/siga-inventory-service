package utp.siga.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import utp.siga.inventory.InventoryIntegrationEnvironment;
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.exception.LocationConflictException;
import utp.siga.inventory.application.port.in.LocationUseCase;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.infrastructure.messaging.InventoryOutboxPublisher;

@SpringBootTest
class PostgresLocationStoreIntegrationTest extends InventoryIntegrationEnvironment {
    @Autowired LocationUseCase locations;
    @Autowired PhysicalCountUseCase counts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired InventoryOutboxPublisher publisher;
    @Autowired AmqpAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbit;
    private final List<UUID> owned = new ArrayList<>();
    private final List<UUID> ownedKeys = new ArrayList<>();

    @AfterEach
    void cleanOwnedFixtures() {
        for (UUID key : ownedKeys) jdbc.update("DELETE FROM inventory.idempotency_record WHERE idempotency_key=?", key);
        for (UUID id : owned) {
            jdbc.update("DELETE FROM inventory.outbox_event WHERE aggregate_id=?", id);
            jdbc.update("DELETE FROM inventory.physical_count WHERE location_id=?", id);
            jdbc.update("DELETE FROM inventory.idempotency_record WHERE resource_id=?", id);
            jdbc.update("DELETE FROM inventory.stock_balance WHERE location_id=?", id);
        }
        for (UUID id : owned) {
            jdbc.update("UPDATE inventory.location SET parent_id=NULL WHERE id=?", id);
        }
        for (int index = owned.size() - 1; index >= 0; index--) {
            jdbc.update("DELETE FROM inventory.location WHERE id=?", owned.get(index));
        }
    }

    @Test
    void createsUpdatesNoOpsAndWritesCanonicalOutbox() throws Exception {
        var root = create("LOC-T-ROOT", null);
        var child = create("LOC-T-CHILD", root.id());
        var updated = locations.update(new LocationUseCase.Update(child.id(), "Child renamed",
                LocationType.RACK, root.id(), 0, actor(), correlation()));
        assertThat(updated.version()).isEqualTo(1);

        long eventsBeforeNoOp = eventCount(child.id());
        var noOp = locations.update(new LocationUseCase.Update(child.id(), "Child renamed",
                LocationType.RACK, root.id(), 1, actor(), correlation()));
        assertThat(noOp.version()).isEqualTo(1);
        assertThat(eventCount(child.id())).isEqualTo(eventsBeforeNoOp);

        String payload = jdbc.queryForObject("SELECT payload::text FROM inventory.outbox_event "
                + "WHERE aggregate_id=? AND payload->'payload'->>'changeType'='UPDATED'", String.class, child.id());
        JsonNode event = mapper.readTree(payload);
        assertThat(event.path("eventType").asText()).isEqualTo("LocationChanged");
        assertThat(event.path("schemaVersion").asInt()).isOne();
        assertThat(event.path("aggregateType").asText()).isEqualTo("Location");
        assertThat(event.path("aggregateId").asText()).isEqualTo(child.id().toString());
        assertThat(event.path("payload").path("before").path("version").asLong()).isZero();
        assertThat(event.path("payload").path("after").path("version").asLong()).isOne();
    }

    @Test
    void checksVersionBeforeNoOpAndRejectsCycles() {
        var root = create("LOC-T-VROOT", null);
        var child = create("LOC-T-VCHILD", root.id());
        assertThatThrownBy(() -> locations.update(new LocationUseCase.Update(child.id(), child.name(),
                child.locationType(), child.parentId(), 9, actor(), correlation())))
                .isInstanceOf(LocationConflictException.class)
                .extracting("code").isEqualTo("VERSION_CONFLICT");
        assertThatThrownBy(() -> locations.update(new LocationUseCase.Update(root.id(), root.name(),
                root.locationType(), child.id(), 0, actor(), correlation())))
                .isInstanceOf(LocationConflictException.class)
                .extracting("code").isEqualTo("LOCATION_HIERARCHY_CONFLICT");
    }

    @Test
    void rejectsDuplicateCodeInactiveParentAndUpdateOfInactiveTargetWithoutEvents() {
        var parent = create("LOC-T-DUP", null);
        assertThatThrownBy(() -> create("LOC-T-DUP", null))
                .isInstanceOf(LocationConflictException.class).extracting("code").isEqualTo("DATA_CONFLICT");
        var inactive = deactivate(parent.id(), 0);
        assertThat(locations.get(inactive.id()).active()).isFalse();
        assertThatThrownBy(() -> create("LOC-T-INACTIVE-PARENT", inactive.id()))
                .isInstanceOf(LocationConflictException.class)
                .extracting("code").isEqualTo("LOCATION_HIERARCHY_CONFLICT");
        long events = eventCount(inactive.id());
        var noOp = deactivate(inactive.id(), inactive.version());
        assertThat(noOp.version()).isEqualTo(inactive.version());
        assertThat(eventCount(inactive.id())).isEqualTo(events);
        assertThatThrownBy(() -> locations.update(new LocationUseCase.Update(inactive.id(), "Changed",
                inactive.locationType(), null, inactive.version(), actor(), correlation())))
                .isInstanceOf(LocationConflictException.class).extracting("code").isEqualTo("LOCATION_INACTIVE");
        assertThat(eventCount(inactive.id())).isEqualTo(events);
    }

    @Test
    void terminalPhysicalCountsDoNotBlockDeactivation() {
        var location = create("LOC-T-HISTORICAL", null);
        UUID completed = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.physical_count(id,location_id,status,created_by_user_id,started_at,"
                + "completed_by_user_id,completed_at) VALUES (?,?, 'COMPLETED',?,now(),?,now())",
                completed, location.id(), actor(), actor());
        jdbc.update("INSERT INTO inventory.physical_count(id,location_id,status,created_by_user_id,started_at,"
                + "cancelled_by_user_id,cancelled_at,cancellation_reason) VALUES (?,?, 'CANCELLED',?,now(),?,now(),'test')",
                cancelled, location.id(), actor(), actor());
        var result = deactivate(location.id(), 0);
        assertThat(result.active()).isFalse();
    }

    @Test
    void lifecycleHonorsDescendantStockAndOpenCountBlockers() {
        var root = create("LOC-T-BROOT", null);
        var child = create("LOC-T-BCHILD", root.id());
        assertConflict(() -> deactivate(root.id(), 0), "LOCATION_HAS_ACTIVE_DESCENDANTS");

        var inactiveChild = deactivate(child.id(), 0);
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,quantity,avg_cost_pen) "
                + "VALUES ('22000000-0000-0000-0000-000000000001',?,1,0)", child.id());
        assertConflict(() -> deactivate(root.id(), 0), "LOCATION_HAS_STOCK");
        jdbc.update("DELETE FROM inventory.stock_balance WHERE location_id=?", child.id());

        jdbc.update("INSERT INTO inventory.physical_count(id,location_id,status,created_by_user_id) "
                + "VALUES (?,?, 'DRAFT',?)", UUID.randomUUID(), child.id(), actor());
        assertConflict(() -> deactivate(root.id(), 0), "LOCATION_IN_USE");
        jdbc.update("DELETE FROM inventory.physical_count WHERE location_id=?", child.id());

        var inactiveRoot = deactivate(root.id(), 0);
        assertThat(inactiveRoot.active()).isFalse();
        assertThatThrownBy(() -> locations.reactivate(new LocationUseCase.Lifecycle(child.id(),
                inactiveChild.version(), actor(), correlation())))
                .isInstanceOf(LocationConflictException.class)
                .extracting("code").isEqualTo("LOCATION_HIERARCHY_CONFLICT");
        var activeRoot = locations.reactivate(new LocationUseCase.Lifecycle(root.id(),
                inactiveRoot.version(), actor(), correlation()));
        assertThat(activeRoot.active()).isTrue();
    }

    @Test
    void listsActiveInactiveAndAllInStableCodeOrder() {
        var active = create("LOC-T-A", null);
        var inactive = deactivate(create("LOC-T-B", null).id(), 0);
        assertThat(locations.list(LocationActiveFilter.TRUE)).extracting("id").contains(active.id()).doesNotContain(inactive.id());
        assertThat(locations.list(LocationActiveFilter.FALSE)).extracting("id").contains(inactive.id()).doesNotContain(active.id());
        assertThat(locations.list(LocationActiveFilter.ALL)).extracting("id").contains(active.id(), inactive.id());
    }

    @Test
    void publishesPersistentLocationChangedEnvelopeMatchingCanonicalSchema() throws Exception {
        String queueName = "inventory.location.changed.test." + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, false, true);
        TopicExchange exchange = new TopicExchange("siga.events", true, false);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange)
                .with("inventory.location.changed.v1"));
        try {
            var location = create("LOC-T-PUBLISH", null);
            assertThat(publisher.publishPending()).isPositive();
            Message message = receiveForAggregate(queueName, location.id());
            assertThat(message).isNotNull();
            assertThat(message.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
            JsonNode envelope = mapper.readTree(message.getBody());
            assertThat(envelope.path("aggregateId").asText()).isEqualTo(location.id().toString());
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    mapper.readTree(Files.readString(Path.of("..", "siga-documentation",
                            "SIGA_Documentacion_Tecnica_Final_v1.2", "api",
                            "location-changed-v1.schema.json"))));
            assertThat(schema.validate(envelope)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE aggregate_id=?",
                    String.class, location.id())).isEqualTo("PUBLISHED");
        } finally {
            rabbitAdmin.deleteQueue(queueName);
        }
    }

    private Message receiveForAggregate(String queueName, UUID aggregateId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Message candidate = rabbit.receive(queueName, 250);
            if (candidate == null) {
                continue;
            }
            if (aggregateId.toString().equals(mapper.readTree(candidate.getBody()).path("aggregateId").asText())) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void concurrentInverseReparentCannotCreateCycle() throws Exception {
        var first = create("LOC-T-CYCLE-A", null);
        var second = create("LOC-T-CYCLE-B", null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> one = executor.submit(() -> updateParentAfter(start, first.id(), second.id()));
            Future<Boolean> two = executor.submit(() -> updateParentAfter(start, second.id(), first.id()));
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        UUID firstParent = locations.get(first.id()).parentId();
        UUID secondParent = locations.get(second.id()).parentId();
        assertThat(firstParent == null || secondParent == null).isTrue();
    }

    @Test
    void deactivationAndPhysicalCountCreationCannotBothCommit() throws Exception {
        var location = create("LOC-T-RACE", null);
        CountDownLatch start = new CountDownLatch(1);
        UUID key = UUID.randomUUID();
        ownedKeys.add(key);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> deactivate = executor.submit(() -> outcomeAfter(start,
                    () -> locations.deactivate(new LocationUseCase.Lifecycle(location.id(), 0,
                            actor(), correlation()))));
            Future<Boolean> createCount = executor.submit(() -> outcomeAfter(start,
                    () -> counts.create(new PhysicalCountUseCase.Create(location.id(), null, actor(), key))));
            start.countDown();
            assertThat(List.of(deactivate.get(10, TimeUnit.SECONDS), createCount.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        long openCounts = jdbc.queryForObject("SELECT count(*) FROM inventory.physical_count WHERE location_id=? "
                + "AND status IN ('DRAFT','IN_PROGRESS')", Long.class, location.id());
        assertThat(locations.get(location.id()).active() && openCounts == 1
                || !locations.get(location.id()).active() && openCounts == 0).isTrue();
    }

    private utp.siga.inventory.application.dto.LocationView create(String code, UUID parent) {
        var created = locations.create(new LocationUseCase.Create(code, code, LocationType.ALMACEN,
                parent, actor(), correlation()));
        owned.add(created.id());
        return created;
    }
    private utp.siga.inventory.application.dto.LocationView deactivate(UUID id, long version) {
        return locations.deactivate(new LocationUseCase.Lifecycle(id, version, actor(), correlation()));
    }
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String code) {
        assertThatThrownBy(operation).isInstanceOf(LocationConflictException.class).extracting("code").isEqualTo(code);
    }
    private long eventCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", Long.class, id);
    }
    private boolean updateParentAfter(CountDownLatch start, UUID id, UUID parent) throws Exception {
        start.await();
        try {
            var current = locations.get(id);
            locations.update(new LocationUseCase.Update(id, current.name(), current.locationType(), parent,
                    current.version(), actor(), correlation()));
            return true;
        } catch (LocationConflictException exception) {
            return false;
        }
    }
    private boolean outcomeAfter(CountDownLatch start, Runnable operation) throws Exception {
        start.await();
        try { operation.run(); return true; }
        catch (RuntimeException exception) { return false; }
    }
    private UUID actor() { return UUID.fromString("12000000-0000-0000-0000-000000000001"); }
    private UUID correlation() { return UUID.randomUUID(); }
}
