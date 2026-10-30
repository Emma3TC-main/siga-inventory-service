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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import utp.siga.inventory.InventoryIntegrationEnvironment;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.exception.AssetConflictException;
import utp.siga.inventory.application.port.in.AssetAdminUseCase;
import utp.siga.inventory.application.query.ListAssetsQuery;
import utp.siga.inventory.infrastructure.messaging.InventoryOutboxPublisher;

@SpringBootTest
class PostgresAssetStoreIntegrationTest extends InventoryIntegrationEnvironment {
    @Autowired AssetAdminUseCase assets;
    @Autowired ListAssetsQuery operational;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired InventoryOutboxPublisher publisher;
    @Autowired AmqpAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbit;
    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> assetIds = new ArrayList<>();
    private final List<UUID> lotIds = new ArrayList<>();

    @BeforeEach void product() { createProduct(true, true); }

    @AfterEach
    void cleanup() {
        for (UUID id : assetIds) {
            jdbc.update("DELETE FROM inventory.outbox_event WHERE aggregate_id=?", id);
            jdbc.update("DELETE FROM inventory.stock_balance WHERE asset_id=?", id);
            jdbc.update("DELETE FROM inventory.asset WHERE id=?", id);
        }
        for (UUID id : lotIds) jdbc.update("DELETE FROM inventory.lot WHERE id=?", id);
        for (UUID id : productIds) {
            jdbc.update("DELETE FROM inventory.product_unit_conversion_ref WHERE product_id=?", id);
            jdbc.update("DELETE FROM inventory.product_ref WHERE product_id=?", id);
        }
    }

    @Test
    void registersCanonicalAssetWithoutStockAndWritesSchemaValidEvent() throws Exception {
        var created = register("ASSET-001", "SERIAL-001", mapper.readTree("{\"voltage\":220}"));
        assertThat(created.operationalStatus()).isEqualTo(AssetOperationalStatus.OPERATIVO);
        assertThat(created.version()).isZero();
        assertThat(created.available()).isFalse();
        assertThat(created.currentLocationId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_balance WHERE asset_id=?",
                Long.class, created.id())).isZero();

        JsonNode envelope = event(created.id(), "REGISTERED");
        assertThat(envelope.path("payload").path("before").isNull()).isTrue();
        assertThat(envelope.path("payload").path("after").has("currentLocationId")).isFalse();
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                mapper.readTree(Files.readString(Path.of("..", "siga-documentation",
                        "SIGA_Documentacion_Tecnica_Final_v1.2", "api", "asset-changed-v1.schema.json"))));
        assertThat(schema.validate(envelope)).isEmpty();
    }

    @Test
    void validatesProductAndTechnicalBoundaryAndTranslatesGlobalDuplicates() throws Exception {
        UUID inactive = createProduct(true, false);
        UUID notSerialized = createProduct(false, true);
        assertThatThrownBy(() -> registerFor(inactive, "A-I", "S-I", mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registerFor(notSerialized, "A-N", "S-N", mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registerFor(productIds.getFirst(), "A-X", "S-X",
                mapper.readTree("{\"locationId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(IllegalArgumentException.class);

        var first = register("UNIQUE-CODE", "UNIQUE-SERIAL", mapper.createObjectNode());
        assertThatThrownBy(() -> register("UNIQUE-CODE", "OTHER-SERIAL", mapper.createObjectNode()))
                .isInstanceOf(AssetConflictException.class).extracting("code").isEqualTo("DATA_CONFLICT");
        assertThatThrownBy(() -> register("OTHER-CODE", "UNIQUE-SERIAL", mapper.createObjectNode()))
                .isInstanceOf(AssetConflictException.class).extracting("code").isEqualTo("DATA_CONFLICT");
        assertThat(eventCount(first.id())).isOne();
    }

    @Test
    void listsEveryStatusAndDerivesAvailabilityLocationAndLot() {
        var unassigned = register("LIST-U", "SER-U", mapper.createObjectNode());
        var inStock = register("LIST-S", "SER-S", mapper.createObjectNode());
        UUID location = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID lot = UUID.randomUUID(); lotIds.add(lot);
        jdbc.update("INSERT INTO inventory.lot(id,product_id,lot_number,status) VALUES (?,?,?,'ACTIVE')",
                lot, inStock.productId(), "ASSET-LOT");
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,lot_id,asset_id,quantity,avg_cost_pen) "
                + "VALUES (?,?,?,?,1,0)", inStock.productId(), location, lot, inStock.id());
        var maintenance = status(inStock, AssetOperationalStatus.EN_MANTENIMIENTO);

        assertThat(assets.list(filter(null, null, null, null, null)).content())
                .extracting("id").contains(unassigned.id(), maintenance.id());
        assertThat(assets.list(filter(inStock.productId(), AssetOperationalStatus.EN_MANTENIMIENTO,
                null, null, null)).content()).extracting("id").contains(maintenance.id());
        assertThat(assets.list(filter(null, null, AssetAvailability.UNASSIGNED, null, null)).content())
                .extracting("id").contains(unassigned.id()).doesNotContain(maintenance.id());
        assertThat(assets.list(filter(null, null, AssetAvailability.IN_STOCK, location, lot)).content())
                .extracting("id").contains(maintenance.id());
        assertThat(assets.get(maintenance.id())).satisfies(detail -> {
            assertThat(detail.available()).isTrue();
            assertThat(detail.currentLocationId()).isEqualTo(location);
            assertThat(detail.currentLotId()).isEqualTo(lot);
        });
    }

    @Test
    void preservesSinglePositiveLocationInvariant() {
        var asset = register("ONE-LOCATION", "ONE-SERIAL", mapper.createObjectNode());
        UUID first = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID second = UUID.fromString("30000000-0000-0000-0000-000000000004");
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,asset_id,quantity,avg_cost_pen) "
                + "VALUES (?,?,?,1,0)", asset.productId(), first, asset.id());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,asset_id,"
                + "quantity,avg_cost_pen) VALUES (?,?,?,1,0)", asset.productId(), second, asset.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void replacesMetadataAndChecksVersionBeforeNoOpWithoutStockMutation() throws Exception {
        var created = register("UPDATE", "UPDATE-S", mapper.readTree("{\"color\":\"red\"}"));
        long events = eventCount(created.id());
        var noOp = assets.update(new AssetAdminUseCase.Update(created.id(), created.brand(), created.model(),
                created.technicalSnapshot(), 0, actor(), correlation()));
        assertThat(noOp.version()).isZero();
        assertThat(noOp.updatedAt()).isEqualTo(created.updatedAt());
        assertThat(eventCount(created.id())).isEqualTo(events);
        assertThatThrownBy(() -> assets.update(new AssetAdminUseCase.Update(created.id(), created.brand(),
                created.model(), created.technicalSnapshot(), 9, actor(), correlation())))
                .isInstanceOf(AssetConflictException.class).extracting("code").isEqualTo("VERSION_CONFLICT");

        var updated = assets.update(new AssetAdminUseCase.Update(created.id(), "Marca", "Modelo",
                mapper.readTree("{\"color\":\"blue\"}"), 0, actor(), correlation()));
        assertThat(updated.version()).isOne();
        assertThat(updated.technicalSnapshot().path("color").asText()).isEqualTo("blue");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_balance WHERE asset_id=?",
                Long.class, created.id())).isZero();
    }

    @Test
    void concurrentUpdatesWithSameVersionCannotLoseChanges() throws Exception {
        var asset = register("CONCURRENT-A", "CONCURRENT-S", mapper.createObjectNode());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> first = executor.submit(() -> updateOutcome(start, asset.id(), "First"));
            Future<Object> second = executor.submit(() -> updateOutcome(start, asset.id(), "Second"));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .filteredOn(value -> value instanceof AssetConflictException).singleElement()
                    .extracting(value -> ((AssetConflictException) value).code()).isEqualTo("VERSION_CONFLICT");
        }
        assertThat(assets.get(asset.id()).version()).isOne();
        assertThat(eventCount(asset.id())).isEqualTo(2);
    }

    @Test
    void enforcesManualLifecycleAndTerminalRetirement() {
        var first = register("LIFE-A", "LIFE-SA", mapper.createObjectNode());
        var maintenance = status(first, AssetOperationalStatus.EN_MANTENIMIENTO);
        assertThat(status(maintenance, AssetOperationalStatus.OPERATIVO).operationalStatus())
                .isEqualTo(AssetOperationalStatus.OPERATIVO);

        var second = register("LIFE-B", "LIFE-SB", mapper.createObjectNode());
        var inoperative = status(second, AssetOperationalStatus.INOPERATIVO);
        var backToMaintenance = status(inoperative, AssetOperationalStatus.EN_MANTENIMIENTO);
        var retired = status(backToMaintenance, AssetOperationalStatus.FUERA_SERVICIO);
        assertThatThrownBy(() -> status(retired, AssetOperationalStatus.EN_MANTENIMIENTO))
                .isInstanceOf(AssetConflictException.class).extracting("code").isEqualTo("ASSET_STATUS_CONFLICT");
        assertThatThrownBy(() -> assets.changeStatus(new AssetAdminUseCase.ChangeStatus(first.id(),
                AssetOperationalStatus.EN_TRANSITO, first.version(), actor(), correlation())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPositiveStockRetirementButAllowsRestrictedAssetsToRetainStock() {
        var asset = register("STOCK-A", "STOCK-S", mapper.createObjectNode());
        UUID location = UUID.fromString("30000000-0000-0000-0000-000000000003");
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,asset_id,quantity,avg_cost_pen) "
                + "VALUES (?,?,?,1,0)", asset.productId(), location, asset.id());
        var maintenance = status(asset, AssetOperationalStatus.EN_MANTENIMIENTO);
        assertThat(assets.get(asset.id()).available()).isTrue();
        assertThatThrownBy(() -> status(maintenance, AssetOperationalStatus.FUERA_SERVICIO))
                .isInstanceOf(AssetConflictException.class).extracting("code").isEqualTo("ASSET_STATUS_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory.stock_balance WHERE asset_id=?",
                BigDecimal.class, asset.id())).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void operationalSelectorKeepsOnlyOperativeAssets() {
        var operative = register("SELECT-A", "SELECT-SA", mapper.createObjectNode());
        var maintenance = status(register("SELECT-B", "SELECT-SB", mapper.createObjectNode()),
                AssetOperationalStatus.EN_MANTENIMIENTO);
        var inoperative = status(register("SELECT-C", "SELECT-SC", mapper.createObjectNode()),
                AssetOperationalStatus.INOPERATIVO);
        var retiredBase = status(register("SELECT-D", "SELECT-SD", mapper.createObjectNode()),
                AssetOperationalStatus.EN_MANTENIMIENTO);
        var retired = status(retiredBase, AssetOperationalStatus.FUERA_SERVICIO);
        assertThat(operational.listAssets(operative.productId(), null, null,
                AssetAvailability.UNASSIGNED, 0, 20).content()).extracting("id")
                .contains(operative.id()).doesNotContain(maintenance.id(), inoperative.id(), retired.id());
    }

    @Test
    void publisherRoutesPersistentAssetChangedMessage() throws Exception {
        String queueName = "inventory.asset.changed.test." + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, false, true);
        TopicExchange exchange = new TopicExchange("siga.events", true, false);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("inventory.asset.changed.v1"));
        try {
            var asset = register("PUBLISH-A", "PUBLISH-S", mapper.createObjectNode());
            assertThat(publisher.publishPending()).isPositive();
            Message message = receiveFor(queueName, asset.id());
            assertThat(message).isNotNull();
            assertThat(message.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
            assertThat(mapper.readTree(message.getBody()).path("aggregateId").asText())
                    .isEqualTo(asset.id().toString());
            assertThat(jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE aggregate_id=?",
                    String.class, asset.id())).isEqualTo("PUBLISHED");
        } finally { rabbitAdmin.deleteQueue(queueName); }
    }

    private UUID createProduct(boolean serial, boolean active) {
        UUID id = UUID.randomUUID(); productIds.add(id);
        jdbc.update("INSERT INTO inventory.product_ref(product_id,sku,product_type,storage_unit_code,base_unit_code,"
                + "requires_serial,active) VALUES (?,?, 'MAQUINARIA','EA','EA',?,?)",
                id, "ASSET-TEST-" + id, serial, active);
        return id;
    }
    private utp.siga.inventory.application.dto.AssetAdminView register(
            String code, String serial, JsonNode snapshot) {
        return registerFor(productIds.getFirst(), code, serial, snapshot);
    }
    private utp.siga.inventory.application.dto.AssetAdminView registerFor(
            UUID product, String code, String serial, JsonNode snapshot) {
        var result = assets.register(new AssetAdminUseCase.Register(product, code, serial, null, null,
                snapshot, actor(), correlation())); assetIds.add(result.id()); return result;
    }
    private utp.siga.inventory.application.dto.AssetAdminView status(
            utp.siga.inventory.application.dto.AssetAdminView asset, AssetOperationalStatus status) {
        return assets.changeStatus(new AssetAdminUseCase.ChangeStatus(asset.id(), status, asset.version(),
                actor(), correlation()));
    }
    private AssetAdminUseCase.Filter filter(UUID product, AssetOperationalStatus status,
            AssetAvailability availability, UUID location, UUID lot) {
        return new AssetAdminUseCase.Filter(product, status, availability, location, lot, 0, 20);
    }
    private JsonNode event(UUID id, String type) throws Exception {
        String value = jdbc.queryForObject("SELECT payload::text FROM inventory.outbox_event WHERE aggregate_id=? "
                + "AND payload->'payload'->>'changeType'=? ORDER BY occurred_at DESC LIMIT 1", String.class, id, type);
        return mapper.readTree(value);
    }
    private long eventCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", Long.class, id);
    }
    private Object updateOutcome(CountDownLatch start, UUID id, String brand) throws InterruptedException {
        start.await();
        try {
            return assets.update(new AssetAdminUseCase.Update(id, brand, null, mapper.createObjectNode(),
                    0, actor(), correlation()));
        } catch (AssetConflictException conflict) { return conflict; }
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
