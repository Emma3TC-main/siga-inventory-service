package utp.siga.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import utp.siga.inventory.InventoryIntegrationEnvironment;
import utp.siga.inventory.application.command.AuthorizeMovementCommand;
import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.port.in.MovementUseCase;
import utp.siga.inventory.application.port.out.EvidenceAvailabilityPort;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementType;
import utp.siga.inventory.infrastructure.messaging.InventoryOutboxPublisher;
import utp.siga.inventory.application.exception.LotUnavailableException;
import utp.siga.inventory.application.exception.AssetUnavailableException;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class D4ConfirmationIntegrationTest extends InventoryIntegrationEnvironment {
    @Autowired MovementUseCase movements;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired InventoryOutboxPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @MockBean EvidenceAvailabilityPort evidenceAvailability;

    @org.junit.jupiter.api.BeforeEach
    void allowCanonicalEvidenceForExistingD4AdjustmentFixtures() {
        org.mockito.Mockito.when(evidenceAvailability.hasAvailableMovementEvidence(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @Test
    void concurrentInverseMultiKeyDestinationsRemainDeadlockFree() throws Exception {
        Fixture f = fixture();
        Movement first = entryWithDetails(f, List.of(
                detail(f.product, f.destination, new BigDecimal("2")),
                detail(f.product, f.extra, new BigDecimal("4"))));
        Movement second = entryWithDetails(f, List.of(
                detail(f.product, f.extra, new BigDecimal("6")),
                detail(f.product, f.destination, new BigDecimal("8"))));
        List<Throwable> outcomes = concurrentOutcomes(() -> confirm(first), () -> confirm(second));
        assertTrue(outcomes.stream().allMatch(t -> t == null), () -> "inverse destination creation failed: " + outcomes);
        assertEquals(1L, count("SELECT count(*) FROM inventory.stock_balance WHERE product_id=? AND location_id=?", f.product, f.destination));
        assertEquals(1L, count("SELECT count(*) FROM inventory.stock_balance WHERE product_id=? AND location_id=?", f.product, f.extra));
        assertBalance(f.product, f.destination, "2.000000", "5.000000");
        assertBalance(f.product, f.extra, "2.000000", "5.000000");
    }

    @Test
    void movementConfirmedContainsApprovedReportingAndAuditSnapshots() throws Exception {
        Fixture f = fixture(); UUID supplier = UUID.randomUUID(); UUID requested = UUID.randomUUID(); UUID confirmed = UUID.randomUUID();
        String supplierCode = "SUP-" + supplier.toString().substring(0, 8); String supplierTaxId = "TAX-" + supplier.toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO inventory.supplier_ref(supplier_id,code,tax_id,business_name,active,source_version,updated_at) VALUES (?,?,?,?,true,1,?)", supplier, supplierCode, supplierTaxId, "Proveedor Snapshot", java.sql.Timestamp.from(Instant.now()));
        Movement entry = movements.createMovement(new CreateMovementCommand(MovementType.ENTRY, requested, "CC-01", "Centro Uno", supplier, "PO", "PO-42", "recepcion", null,
                List.of(detail(f.product, f.destination, new BigDecimal("2")))), UUID.randomUUID());
        movements.confirmMovement(new ConfirmMovementCommand(entry.getId(), UUID.randomUUID(), confirmed, UUID.randomUUID()));
        JsonNode entryEvent = eventFor(entry.getId()); JsonNode entryMovement = entryEvent.path("payload").path("movement");
        assertEquals("CC-01", entryMovement.path("costCenterCode").asText()); assertEquals("Centro Uno", entryMovement.path("costCenterNameSnapshot").asText());
        assertEquals(supplier.toString(), entryMovement.path("supplierId").asText()); assertEquals("Proveedor Snapshot", entryMovement.path("supplierNameSnapshot").asText());
        assertEquals(supplierTaxId, entryMovement.path("supplierTaxIdSnapshot").asText()); assertEquals("PO", entryMovement.path("referenceDocType").asText());
        assertEquals("PO-42", entryMovement.path("referenceDocNumber").asText()); assertEquals(confirmed.toString(), entryMovement.path("confirmedByUserId").asText());
        assertTrue(movementSchema().validate(entryEvent).isEmpty());

        Movement adjustment = movements.createMovement(new CreateMovementCommand(MovementType.ADJUST_POSITIVE, null, null, null, null, "ADJ", "ADJ-1", "conteo", null,
                List.of(detail(f.product, f.extra, BigDecimal.ONE))), UUID.randomUUID());
        authorize(adjustment); movements.confirmMovement(new ConfirmMovementCommand(adjustment.getId(), UUID.randomUUID(), confirmed, UUID.randomUUID()));
        JsonNode adjustmentEvent = eventFor(adjustment.getId()); JsonNode adjustmentMovement = adjustmentEvent.path("payload").path("movement");
        assertTrue(adjustmentMovement.path("isSensitive").asBoolean()); assertEquals("INVENTORY_ADJUSTMENT", adjustmentMovement.path("sensitivityReason").asText());
        assertTrue(adjustmentMovement.path("authorizedByUserId").isTextual()); assertEquals("conteo", adjustmentMovement.path("reason").asText());
        assertTrue(movementSchema().validate(adjustmentEvent).isEmpty());
    }

    @Test
    void publisherHasScheduledWorkerMethod() throws Exception {
        Scheduled scheduled = InventoryOutboxPublisher.class.getDeclaredMethod("publishScheduled").getAnnotation(Scheduled.class);
        assertTrue(scheduled != null); assertEquals("${inventory.outbox.publisher.fixed-delay-ms}", scheduled.fixedDelayString());
    }

    @Test
    void multiDetailFailureRollsBackStocksDetailsOutboxAndIdempotency() {
        Fixture f = fixture();
        var first = new CreateMovementCommand.Detail(f.product, BigDecimal.ONE, "EA", null, f.destination, null, null, "PEN", BigDecimal.ONE, null, null, null);
        var second = new CreateMovementCommand.Detail(f.product, BigDecimal.ONE, "EA", null, f.extra, null, null, "PEN", null, null, null, null);
        Movement movement = movements.createMovement(new CreateMovementCommand(MovementType.ENTRY, null, null, null, null, null, null, null, null, List.of(first, second)), UUID.randomUUID());
        UUID key = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), key, UUID.randomUUID(), UUID.randomUUID())));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM inventory.movement WHERE id=?", String.class, movement.getId()));
        assertEquals(0L, count("SELECT count(*) FROM inventory.stock_balance WHERE product_id=?", f.product));
        assertEquals(0L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", movement.getId()));
        assertEquals(0L, count("SELECT count(*) FROM inventory.idempotency_record WHERE idempotency_key=?", key));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM inventory.movement_detail WHERE movement_id=? AND (base_unit_cost_pen IS NOT NULL OR applied_avg_cost_pen IS NOT NULL)", Long.class, movement.getId()));
    }

    @Test
    void operatorCannotConfirmDraftAndNothingIsWritten() {
        Fixture f = fixture();
        Movement movement = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE,
                BigDecimal.ONE, "PEN", null);
        UUID key = UUID.randomUUID();

        assertThrows(utp.siga.inventory.domain.exception.InvalidMovementStateException.class,
                () -> movements.confirmMovement(new ConfirmMovementCommand(
                        movement.getId(), key, movement.getRegisteredByUserId(), UUID.randomUUID(), false)));

        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM inventory.movement WHERE id=?", String.class, movement.getId()));
        assertEquals(0L, count("SELECT count(*) FROM inventory.stock_balance WHERE product_id=?", f.product));
        assertEquals(0L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", movement.getId()));
        assertEquals(0L, count("SELECT count(*) FROM inventory.idempotency_record WHERE idempotency_key=?", key));
    }

    @Test
    void authorizerCannotBypassSubmitForSensitiveDraft() {
        Fixture f = fixture();
        Movement movement = draft(f, MovementType.ADJUST_POSITIVE, null, f.destination,
                BigDecimal.ONE, BigDecimal.ONE, "PEN", "count correction");
        UUID key = UUID.randomUUID();

        assertThrows(utp.siga.inventory.domain.exception.InvalidMovementStateException.class,
                () -> movements.confirmMovement(new ConfirmMovementCommand(
                        movement.getId(), key, UUID.randomUUID(), UUID.randomUUID(), true)));

        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM inventory.movement WHERE id=?", String.class, movement.getId()));
        assertEquals(0L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", movement.getId()));
    }

    @Test
    void concurrentSameKeyAppliesMovementAndOutboxOnlyOnce() throws Exception {
        Fixture f = fixture(); Movement movement = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE, BigDecimal.ONE, "PEN", null);
        UUID key = UUID.randomUUID(), actor = UUID.randomUUID(), correlation = UUID.randomUUID();
        List<Movement> results = concurrent(() -> movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), key, actor, correlation)),
                () -> movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), key, actor, correlation)));
        assertEquals(2, results.size()); assertTrue(results.stream().allMatch(m -> m.getStatus().name().equals("CONFIRMED")));
        assertBalance(f.product, f.destination, "1.000000", "1.000000");
        assertEquals(1L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", movement.getId()));
    }

    @Test
    void concurrentCompetingExitsNeverMakeStockNegative() throws Exception {
        Fixture f = fixture(); seedBalance(f.product, f.origin, "10", "2");
        Movement first = draft(f, MovementType.EXIT, f.origin, null, new BigDecimal("7"), null, "PEN", null);
        Movement second = draft(f, MovementType.EXIT, f.origin, null, new BigDecimal("7"), null, "PEN", null);
        List<Throwable> outcomes = concurrentOutcomes(() -> confirm(first), () -> confirm(second));
        assertEquals(1L, outcomes.stream().filter(t -> t == null).count());
        assertEquals(1L, outcomes.stream().filter(t -> t instanceof IllegalStateException).count());
        assertBalance(f.product, f.origin, "3.000000", "2.000000");
        assertEquals(1L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=? OR aggregate_id=?", first.getId(), second.getId()));
    }

    @Test
    void crossedTransfersUseDeterministicOrderAndLeaveConsistentBalances() throws Exception {
        Fixture f = fixture(); seedBalance(f.product, f.origin, "10", "2"); seedBalance(f.product, f.destination, "10", "2");
        Movement forward = draft(f, MovementType.TRANSFER, f.origin, f.destination, new BigDecimal("2"), null, "PEN", null);
        Movement reverse = draft(f, MovementType.TRANSFER, f.destination, f.origin, new BigDecimal("3"), null, "PEN", null);
        List<Throwable> outcomes = concurrentOutcomes(() -> confirm(forward), () -> confirm(reverse));
        assertTrue(outcomes.stream().allMatch(t -> t == null), () -> "cross-transfer failed: " + outcomes);
        assertBalance(f.product, f.origin, "11.000000", "2.000000"); assertBalance(f.product, f.destination, "9.000000", "2.000000");
    }

    @Test
    void concurrentEntriesCreateOneDestinationBalance() throws Exception {
        Fixture f = fixture();
        Movement first = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE, new BigDecimal("2"), "PEN", null);
        Movement second = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE, new BigDecimal("4"), "PEN", null);
        List<Throwable> outcomes = concurrentOutcomes(() -> confirm(first), () -> confirm(second));
        assertTrue(outcomes.stream().allMatch(t -> t == null), () -> "destination creation failed: " + outcomes);
        assertEquals(1L, count("SELECT count(*) FROM inventory.stock_balance WHERE product_id=? AND location_id=?", f.product, f.destination));
        assertBalance(f.product, f.destination, "2.000000", "3.000000");
    }

    @Test
    void publisherUsesBrokerConfirmAndRetriesWithTheSameEventId(CapturedOutput output) throws Exception {
        Fixture f = fixture(); Movement movement = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE, BigDecimal.ONE, "PEN", null); confirm(movement);
        UUID eventId = jdbc.queryForObject("SELECT id FROM inventory.outbox_event WHERE aggregate_id=?", UUID.class, movement.getId());
        String queue = "inventory.d4.publisher." + UUID.randomUUID(); RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        TopicExchange exchange = new TopicExchange("siga.events", true, false); admin.declareExchange(exchange); admin.declareQueue(new Queue(queue, true)); admin.declareBinding(BindingBuilder.bind(new Queue(queue, true)).to(exchange).with("inventory.movement.confirmed.v1"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE id=?", String.class, eventId));
        publisher.publishScheduled();
        Message published = receiveEvent(queue, eventId);
        assertTrue(published != null); assertEquals("PERSISTENT", published.getMessageProperties().getReceivedDeliveryMode().name());
        JsonNode event = json.readTree(published.getBody()); assertEquals(eventId.toString(), event.path("eventId").asText()); assertTrue(movementSchema().validate(event).isEmpty());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE id=?", String.class, eventId));
        Movement failedMovement = draft(f, MovementType.ENTRY, null, f.extra, BigDecimal.ONE, BigDecimal.ONE, "PEN", null); confirm(failedMovement);
        UUID failedEventId = jdbc.queryForObject("SELECT id FROM inventory.outbox_event WHERE aggregate_id=?", UUID.class, failedMovement.getId());
        admin.deleteExchange("siga.events");
        assertEquals(0, publisher.publishPending());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE id=?", String.class, failedEventId));
        assertEquals(1L, jdbc.queryForObject("SELECT attempts FROM inventory.outbox_event WHERE id=?", Long.class, failedEventId));
        assertTrue(output.getOut().contains(failedEventId.toString()));
        admin.declareExchange(exchange); admin.declareBinding(BindingBuilder.bind(new Queue(queue, true)).to(exchange).with("inventory.movement.confirmed.v1"));
        jdbc.update("UPDATE inventory.outbox_event SET next_attempt_at=now() WHERE id=?", failedEventId);
        assertEquals(1, publisher.publishPending());
        Message retried = receiveEvent(queue, failedEventId); assertTrue(retried != null);
        assertEquals(failedEventId.toString(), json.readTree(retried.getBody()).path("eventId").asText());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM inventory.outbox_event WHERE id=?", String.class, failedEventId));
    }

    @Test
    void entryPersistsWeightedAverageOutboxAndSchema() throws Exception {
        Fixture f = fixture();
        Movement movement = draft(f, MovementType.ENTRY, null, f.destination, new BigDecimal("2"), new BigDecimal("1.000000"), "PEN", null);
        confirm(movement);
        assertBalance(f.product, f.destination, "2.000000", "1.000000");
        String payload = jdbc.queryForObject("SELECT payload::text FROM inventory.outbox_event WHERE aggregate_id=?", String.class, movement.getId());
        JsonNode event = json.readTree(payload);
        assertEquals("MovementConfirmed", event.path("eventType").asText());
        assertEquals("inventory-service", event.path("producer").asText());
        assertEquals(1, event.path("payload").path("stockBalances").size());
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(json.readTree(Files.readString(Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2", "api", "movement-confirmed-v1.schema.json"))));
        assertTrue(schema.validate(event).isEmpty());
    }

    @Test
    void exitAndNegativeAdjustmentPreserveLockedAverage() {
        Fixture f = fixture(); seedBalance(f.product, f.origin, "5", "3.333333");
        Movement exit = draft(f, MovementType.EXIT, f.origin, null, new BigDecimal("2"), null, "PEN", null);
        confirm(exit); assertBalance(f.product, f.origin, "3.000000", "3.333333");
        assertEquals(new BigDecimal("3.333333"), jdbc.queryForObject("SELECT applied_avg_cost_pen FROM inventory.movement_detail WHERE movement_id=?", BigDecimal.class, exit.getId()));
        Movement adjust = draft(f, MovementType.ADJUST_NEGATIVE, f.origin, null, BigDecimal.ONE, null, "PEN", "count");
        authorize(adjust); confirm(adjust); assertBalance(f.product, f.origin, "2.000000", "3.333333");
    }

    @Test
    void transferMixesDestinationAndPositiveAdjustmentRulesAreEnforced() {
        Fixture f = fixture(); seedBalance(f.product, f.origin, "5", "4"); seedBalance(f.product, f.destination, "2", "10");
        Movement transfer = draft(f, MovementType.TRANSFER, f.origin, f.destination, new BigDecimal("2"), null, "PEN", null);
        confirm(transfer); assertBalance(f.product, f.origin, "3.000000", "4.000000"); assertBalance(f.product, f.destination, "4.000000", "7.000000");
        Movement invalid = draft(f, MovementType.ADJUST_POSITIVE, null, f.extra, BigDecimal.ONE, null, "PEN", "count");
        authorize(invalid);
        assertThrows(IllegalStateException.class, () -> confirm(invalid));
        assertEquals("AUTHORIZED", jdbc.queryForObject("SELECT status FROM inventory.movement WHERE id=?", String.class, invalid.getId()));
    }

    @Test
    void idempotencyReplayDoesNotDuplicateOutboxAndConflictingPayloadIsRejected() {
        Fixture f = fixture(); Movement movement = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE, BigDecimal.ONE, "PEN", null);
        UUID key = UUID.randomUUID(); UUID actor = UUID.randomUUID(); UUID correlation = UUID.randomUUID();
        movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), key, actor, correlation));
        movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), key, actor, correlation));
        assertEquals(1L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", movement.getId()));
        assertThrows(IllegalStateException.class, () -> movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), key, UUID.randomUUID(), correlation)));
    }

    @Test
    void entryRequiresCostAndRoundsHalfUp() {
        Fixture f = fixture();
        Movement missing = draft(f, MovementType.ENTRY, null, f.destination, BigDecimal.ONE, null, "PEN", null);
        assertThrows(IllegalArgumentException.class, () -> confirm(missing));
        Movement rounded = draft(f, MovementType.ENTRY, null, f.extra, BigDecimal.valueOf(3), new BigDecimal("1.0000015"), "PEN", null);
        confirm(rounded);
        assertEquals(new BigDecimal("1.000002"), jdbc.queryForObject("SELECT base_unit_cost_pen FROM inventory.movement_detail WHERE movement_id=?", BigDecimal.class, rounded.getId()));
    }

    @Test
    void zeroStockLotRemainsActiveOnFirstEntryAndUnavailableLotsAreRejected() {
        Fixture f = fixture();
        UUID lot = seedLot(f.product, "ACTIVE", null);
        Movement entry = draftWithLot(f, MovementType.ENTRY, null, f.destination, lot,
                BigDecimal.ONE, BigDecimal.ONE);
        confirm(entry);
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM inventory.lot WHERE id=?", String.class, lot));

        UUID blocked = seedLot(f.product, "BLOCKED", null);
        Movement rejected = draftWithLot(f, MovementType.ENTRY, null, f.extra, blocked,
                BigDecimal.ONE, BigDecimal.ONE);
        UUID key = UUID.randomUUID();
        assertThrows(LotUnavailableException.class, () -> movements.confirmMovement(
                new ConfirmMovementCommand(rejected.getId(), key, UUID.randomUUID(), UUID.randomUUID())));
        assertEquals(0L, count("SELECT count(*) FROM inventory.idempotency_record WHERE idempotency_key=?", key));
        assertEquals(0L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", rejected.getId()));
    }

    @Test
    void confirmationDepletesOnlyOnPositiveToAggregateZeroAndWritesCanonicalEvent() throws Exception {
        Fixture f = fixture(); UUID lot = seedLot(f.product, "ACTIVE", null);
        seedLotBalance(f.product, f.origin, lot, "3", "2");
        Movement partial = draftWithLot(f, MovementType.EXIT, f.origin, null, lot,
                BigDecimal.ONE, null);
        confirm(partial);
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM inventory.lot WHERE id=?", String.class, lot));

        Movement finalExit = draftWithLot(f, MovementType.EXIT, f.origin, null, lot,
                new BigDecimal("2"), null);
        confirm(finalExit);
        assertEquals("DEPLETED", jdbc.queryForObject("SELECT status FROM inventory.lot WHERE id=?", String.class, lot));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM inventory.lot WHERE id=?", Long.class, lot));
        JsonNode changed = json.readTree(jdbc.queryForObject("SELECT payload::text FROM inventory.outbox_event "
                + "WHERE aggregate_id=? AND event_type='LotChanged'", String.class, lot));
        assertEquals("STATUS_CHANGED", changed.path("payload").path("changeType").asText());
        assertTrue(changed.path("payload").path("actorUserId").isNull());
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                json.readTree(Files.readString(Path.of("..", "siga-documentation",
                        "SIGA_Documentacion_Tecnica_Final_v1.2", "api", "lot-changed-v1.schema.json"))));
        assertTrue(schema.validate(changed).isEmpty());
    }

    @Test
    void aggregateAcrossLocationsPreventsPrematureDepletionAndExpiryIsRevalidated() {
        Fixture f = fixture(); UUID lot = seedLot(f.product, "ACTIVE", null);
        seedLotBalance(f.product, f.origin, lot, "1", "2");
        seedLotBalance(f.product, f.extra, lot, "1", "2");
        Movement exit = draftWithLot(f, MovementType.EXIT, f.origin, null, lot, BigDecimal.ONE, null);
        confirm(exit);
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM inventory.lot WHERE id=?", String.class, lot));

        UUID expired = seedLot(f.product, "ACTIVE", java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1));
        Movement rejected = draftWithLot(f, MovementType.ENTRY, null, f.destination, expired,
                BigDecimal.ONE, BigDecimal.ONE);
        assertThrows(LotUnavailableException.class, () -> confirm(rejected));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM inventory.movement WHERE id=?",
                String.class, rejected.getId()));
    }

    @Test
    void assetStatusIsRevalidatedAtConfirmationWithoutChangingLifecycle() {
        Fixture f = fixture();
        UUID operative = seedAsset(f.product, "OPERATIVO");
        Movement entry = draftWithAsset(f, MovementType.ENTRY, null, f.destination, operative,
                BigDecimal.ONE, BigDecimal.ONE);
        confirm(entry);
        assertEquals("OPERATIVO", jdbc.queryForObject(
                "SELECT operational_status FROM inventory.asset WHERE id=?", String.class, operative));
        assertEquals(BigDecimal.ONE.setScale(6), jdbc.queryForObject(
                "SELECT quantity FROM inventory.stock_balance WHERE asset_id=?", BigDecimal.class, operative));

        Movement duplicateEntry = draftWithAsset(f, MovementType.ENTRY, null, f.extra, operative,
                BigDecimal.ONE, BigDecimal.ONE);
        assertThrows(AssetUnavailableException.class, () -> confirm(duplicateEntry));
        assertEquals(1L, count("SELECT count(*) FROM inventory.stock_balance WHERE asset_id=? AND quantity>0",
                operative));

        UUID changedAfterSelection = seedAsset(f.product, "OPERATIVO");
        Movement rejected = draftWithAsset(f, MovementType.ENTRY, null, f.extra, changedAfterSelection,
                BigDecimal.ONE, BigDecimal.ONE);
        jdbc.update("UPDATE inventory.asset SET operational_status='EN_MANTENIMIENTO' WHERE id=?",
                changedAfterSelection);
        assertThrows(AssetUnavailableException.class, () -> confirm(rejected));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM inventory.movement WHERE id=?",
                String.class, rejected.getId()));
        assertEquals(0L, count("SELECT count(*) FROM inventory.stock_balance WHERE asset_id=?",
                changedAfterSelection));
    }

    @Test
    void serializedAssetRemainsOperativeAcrossCanonicalStockMovements() {
        Fixture f = fixture();
        UUID asset = seedAsset(f.product, "OPERATIVO");
        confirm(draftWithAsset(f, MovementType.ENTRY, null, f.origin, asset,
                BigDecimal.ONE, new BigDecimal("5")));

        Movement transfer = draftWithAsset(f, MovementType.TRANSFER, f.origin, f.destination, asset,
                BigDecimal.ONE, null);
        authorize(transfer); confirm(transfer);
        assertEquals(f.destination, jdbc.queryForObject(
                "SELECT location_id FROM inventory.stock_balance WHERE asset_id=? AND quantity=1",
                UUID.class, asset));

        Movement exit = draftWithAsset(f, MovementType.EXIT, f.destination, null, asset,
                BigDecimal.ONE, null);
        authorize(exit); confirm(exit);
        assertEquals(0L, count("SELECT count(*) FROM inventory.stock_balance WHERE asset_id=? AND quantity>0", asset));

        Movement positive = draftWithAsset(f, MovementType.ADJUST_POSITIVE, null, f.extra, asset,
                BigDecimal.ONE, new BigDecimal("7"));
        authorize(positive); confirm(positive);
        Movement negative = draftWithAsset(f, MovementType.ADJUST_NEGATIVE, f.extra, null, asset,
                BigDecimal.ONE, null);
        authorize(negative); confirm(negative);

        assertEquals("OPERATIVO", jdbc.queryForObject(
                "SELECT operational_status FROM inventory.asset WHERE id=?", String.class, asset));
        assertEquals(0L, count("SELECT count(*) FROM inventory.stock_balance WHERE asset_id=? AND quantity>0", asset));
        assertEquals(5L, count("SELECT count(*) FROM inventory.outbox_event e "
                + "JOIN inventory.movement_detail d ON d.movement_id=e.aggregate_id "
                + "WHERE e.event_type='MovementConfirmed' AND d.asset_id=?", asset));
    }

    private Movement draft(Fixture f, MovementType type, UUID origin, UUID destination, BigDecimal qty, BigDecimal cost, String currency, String reason) {
        var detail = new CreateMovementCommand.Detail(f.product, qty, "EA", origin, destination, null, null, currency, cost, null, null, null);
        return movements.createMovement(new CreateMovementCommand(type, null, null, null, null, null, null, reason, null, List.of(detail)), UUID.randomUUID());
    }
    private Movement draftWithLot(Fixture f, MovementType type, UUID origin, UUID destination, UUID lot,
            BigDecimal qty, BigDecimal cost) {
        var detail = new CreateMovementCommand.Detail(f.product, qty, "EA", origin, destination, lot, null,
                "PEN", cost, null, null, null);
        return movements.createMovement(new CreateMovementCommand(type, null, null, null, null, null, null,
                null, null, List.of(detail)), UUID.randomUUID());
    }
    private Movement draftWithAsset(Fixture f, MovementType type, UUID origin, UUID destination, UUID asset,
            BigDecimal qty, BigDecimal cost) {
        var detail = new CreateMovementCommand.Detail(f.product, qty, "EA", origin, destination, null, asset,
                "PEN", cost, null, null, null);
        String reason = type == MovementType.ADJUST_POSITIVE || type == MovementType.ADJUST_NEGATIVE
                ? "Serialized asset adjustment"
                : null;
        return movements.createMovement(new CreateMovementCommand(type, null, null, null, null, null, null,
                reason, null, List.of(detail)), UUID.randomUUID());
    }
    private UUID seedLot(UUID product, String status, java.time.LocalDate expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.lot(id,product_id,lot_number,status,expires_at,version) VALUES (?,?,?,?,?,0)",
                id, product, "LOT-" + id, status, expiresAt);
        return id;
    }
    private void seedLotBalance(UUID product, UUID location, UUID lot, String qty, String avg) {
        jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,lot_id,quantity,avg_cost_pen) "
                + "VALUES (?,?,?,?,?)", product, location, lot, new BigDecimal(qty), new BigDecimal(avg));
    }
    private UUID seedAsset(UUID product, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.asset(id,product_id,asset_code,serial_number,operational_status,version) "
                + "VALUES (?,?,?,?,?,0)", id, product, "ASSET-" + id, "SERIAL-" + id, status);
        return id;
    }
    private CreateMovementCommand.Detail detail(UUID product, UUID destination, BigDecimal cost) { return new CreateMovementCommand.Detail(product, BigDecimal.ONE, "EA", null, destination, null, null, "PEN", cost, null, null, null); }
    private Movement entryWithDetails(Fixture f, List<CreateMovementCommand.Detail> details) { return movements.createMovement(new CreateMovementCommand(MovementType.ENTRY, null, null, null, null, null, null, null, null, details), UUID.randomUUID()); }
    private void authorize(Movement movement) { movements.submitMovement(new SubmitMovementCommand(movement.getId()), true); movements.authorizeMovement(new AuthorizeMovementCommand(movement.getId(), "approved"), UUID.randomUUID(), true); }
    private void confirm(Movement movement) { movements.confirmMovement(new ConfirmMovementCommand(movement.getId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())); }
    private void seedBalance(UUID product, UUID location, String qty, String avg) { jdbc.update("INSERT INTO inventory.stock_balance(product_id,location_id,quantity,avg_cost_pen) VALUES (?,?,?,?)", product,location,new BigDecimal(qty),new BigDecimal(avg)); }
    private void assertBalance(UUID product, UUID location, String qty, String avg) { var row=jdbc.queryForMap("SELECT quantity,avg_cost_pen FROM inventory.stock_balance WHERE product_id=? AND location_id=?",product,location); assertEquals(new BigDecimal(qty),row.get("quantity"));assertEquals(new BigDecimal(avg),row.get("avg_cost_pen")); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> a = executor.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            Future<T> b = executor.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
        return java.util.Arrays.asList(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    private List<Throwable> concurrentOutcomes(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> a = executor.submit(() -> outcome(first, ready, start)); Future<Throwable> b = executor.submit(() -> outcome(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return java.util.Arrays.asList(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    private Throwable outcome(ThrowingRunnable operation, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); start.await(); try { operation.run(); return null; } catch (Throwable failure) { return failure; }
    }
    private Message receiveEvent(String queue, UUID eventId) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            Message message = rabbit.receive(queue, 1_000);
            if (message != null && eventId.toString().equals(json.readTree(message.getBody()).path("eventId").asText())) return message;
        }
        return null;
    }
    private JsonNode eventFor(UUID movementId) throws Exception { return json.readTree(jdbc.queryForObject("SELECT payload::text FROM inventory.outbox_event WHERE aggregate_id=?", String.class, movementId)); }
    private com.networknt.schema.JsonSchema movementSchema() throws Exception { return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(json.readTree(Files.readString(Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2", "api", "movement-confirmed-v1.schema.json")))); }
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    private Fixture fixture(){ UUID product=UUID.randomUUID(), origin=UUID.randomUUID(), destination=UUID.randomUUID(), extra=UUID.randomUUID(); jdbc.update("INSERT INTO inventory.product_ref(product_id,sku,product_type,storage_unit_code,base_unit_code,min_stock) VALUES (?,?, 'MATERIAL','EA','EA',0)",product,"SKU-"+product.toString().substring(0,8)); for(UUID id:List.of(origin,destination,extra))jdbc.update("INSERT INTO inventory.location(id,code,name,location_type) VALUES (?,?,?, 'RECEPCION')",id,"L-"+id.toString().substring(0,8),"loc"); return new Fixture(product,origin,destination,extra); }
    private record Fixture(UUID product, UUID origin, UUID destination, UUID extra) { }
}
