package utp.siga.inventory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.usecase.DefaultMovementUseCase;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementAuthorization;
import utp.siga.inventory.domain.model.MovementDetail;
import utp.siga.inventory.domain.model.MovementStatus;
import utp.siga.inventory.domain.model.MovementType;

@JdbcTest
@Testcontainers
class PostgresMovementRepositoryTest {
    private static final AtomicLong FIXTURE_CODES = new AtomicLong(900_000);
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_local_test")
            .withUsername("siga_inventory_test")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + postgres.getMappedPort(5432) + "/siga_inventory_local_test");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.schemas", () -> "inventory");
        registry.add("spring.flyway.default-schema", () -> "inventory");
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void savesAndRehydratesDraftWithImmutableDetailSnapshot() {
        Fixture fixture = fixture();
        PostgresMovementRepository repository = new PostgresMovementRepository(jdbc);
        Movement movement = fixture.draft();

        repository.save(movement);
        Movement found = repository.findById(movement.getId()).orElseThrow();

        assertEquals(MovementStatus.DRAFT, found.getStatus());
        assertEquals(1, found.getDetails().size());
        assertEquals(0, BigDecimal.TEN.compareTo(found.getDetails().getFirst().getEnteredQuantity()));
        assertEquals(fixture.destinationId(), found.getDetails().getFirst().getDestinationLocationId());
    }

    @Test
    void allowsCanonicalDraftToPendingToAuthorizedTransition() {
        Fixture fixture = fixture();
        PostgresMovementRepository repository = new PostgresMovementRepository(jdbc);
        Movement movement = fixture.draft();
        movement.evaluateSensitivity(true, "review");
        repository.save(movement);
        movement.submit();
        repository.save(movement);
        UUID authorizer = UUID.randomUUID();
        movement.authorize(authorizer, true, "approved");
        repository.save(movement);

        Movement found = repository.findById(movement.getId()).orElseThrow();
        assertEquals(MovementStatus.AUTHORIZED, found.getStatus());
        assertEquals("APPROVED", found.getAuthorizations().getFirst().getDecision());
        assertEquals(authorizer, found.getAuthorizations().getFirst().getAuthorizedByUserId());
        assertTrue(found.getAuthorizations().getFirst().isStepUpVerified());
    }

    @Test
    void staleDraftCannotOverwriteNewerPendingStateOrDetail() {
        Fixture fixture = fixture();
        PostgresMovementRepository repository = new PostgresMovementRepository(jdbc);
        Movement movement = fixture.draft();
        repository.save(movement);
        Movement staleDraft = repository.findById(movement.getId()).orElseThrow();

        movement.evaluateSensitivity(true, "review");
        movement.submit();
        repository.save(movement);

        assertThrows(IllegalStateException.class, () -> repository.save(staleDraft));
        assertEquals("PENDING_AUTHORIZATION", jdbc.queryForObject(
                "SELECT status FROM inventory.movement WHERE id=?", String.class, movement.getId()));
        assertEquals(new BigDecimal("10.000000"), jdbc.queryForObject(
                "SELECT entered_quantity FROM inventory.movement_detail WHERE movement_id=?", BigDecimal.class, movement.getId()));
    }

    @Test
    void preservesAuthorizationHistoryWhenMovementChangesAgain() {
        Fixture fixture = fixture();
        PostgresMovementRepository repository = new PostgresMovementRepository(jdbc);
        Movement movement = fixture.draft();
        movement.evaluateSensitivity(true, "review");
        repository.save(movement);
        movement.submit();
        repository.save(movement);
        movement.authorize(UUID.randomUUID(), true, "approved");
        repository.save(movement);
        MovementAuthorization decision = repository.findById(movement.getId()).orElseThrow().getAuthorizations().getFirst();

        movement.cancel();
        repository.save(movement);

        Movement restored = repository.findById(movement.getId()).orElseThrow();
        MovementAuthorization preserved = restored.getAuthorizations().getFirst();
        assertEquals(MovementStatus.CANCELLED, restored.getStatus());
        assertEquals(decision.getDecision(), preserved.getDecision());
        assertEquals(decision.getAuthorizedByUserId(), preserved.getAuthorizedByUserId());
        assertEquals(decision.getDecidedAt(), preserved.getDecidedAt());
    }

    @Test
    void rehydratesExactPersistedAuthorizationDecisionTime() {
        Fixture fixture = fixture();
        PostgresMovementRepository repository = new PostgresMovementRepository(jdbc);
        Instant requestedAt = Instant.parse("2026-10-05T20:00:00Z");
        Instant decidedAt = Instant.parse("2026-10-05T20:01:02Z");
        MovementAuthorization authorization = MovementAuthorization.rehydrate(UUID.randomUUID(), requestedAt, decidedAt,
                UUID.randomUUID(), "APPROVED", true, "approved");
        Movement movement = fixture.draftBuilder().status(MovementStatus.AUTHORIZED)
                .isSensitive(true).sensitivityReason("review").addAuthorization(authorization).build();

        repository.save(movement);

        MovementAuthorization restored = repository.findById(movement.getId()).orElseThrow()
                .getAuthorizations().getFirst();
        assertEquals(requestedAt, restored.getRequestedAt());
        assertEquals(decidedAt, restored.getDecidedAt());
    }

    @Test
    void readsProductAndSupplierReferencesFromPostgresProjection() {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.product_ref(product_id, sku, product_type, storage_unit_code, base_unit_code,
                    min_stock, requires_lot, requires_heat_number, requires_serial, requires_expiry, active, source_version)
                VALUES (?, ?, 'MATERIAL', 'BOX', 'EA', 1, false, false, false, false, true, 7)
                """, productId, "SKU-" + productId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory.product_unit_conversion_ref(product_id, from_unit_code, to_unit_code, factor, active, source_version)
                VALUES (?, 'BOX', 'EA', 12.000000, true, 7)
                """, productId);
        jdbc.update("""
                INSERT INTO inventory.supplier_ref(supplier_id, code, tax_id, business_name, active, source_version)
                VALUES (?, ?, ?, 'Proveedor de prueba', true, 4)
                """, supplierId, "SUP-" + supplierId.toString().substring(0, 8),
                supplierId.toString().replace("-", "").substring(0, 11));

        var product = new PostgresProductRefStore(jdbc).getProduct(productId).orElseThrow();
        var supplier = new PostgresSupplierRefStore(jdbc).getSupplier(supplierId).orElseThrow();

        assertEquals(7, product.sourceVersion());
        assertEquals(new BigDecimal("12.000000"), product.conversions().getFirst().factor());
        assertTrue(product.conversions().getFirst().active());
        assertEquals("Proveedor de prueba", supplier.businessName());
        assertTrue(supplier.active());
        assertFalse(new PostgresProductRefStore(jdbc).getProduct(UUID.randomUUID()).isPresent());
    }

    @Test
    void createsBaseQuantitySnapshotUsingThePersistedDirectConversion() {
        UUID productId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.product_ref(product_id, sku, product_type, storage_unit_code, base_unit_code)
                VALUES (?, ?, 'MATERIAL', 'BOX', 'EA')
                """, productId, "SKU-" + productId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory.product_unit_conversion_ref(product_id, from_unit_code, to_unit_code, factor)
                VALUES (?, 'BOX', 'EA', 12.000000)
                """, productId);
        jdbc.update("""
                INSERT INTO inventory.location(id, code, name, location_type)
                VALUES (?, ?, 'Destino', 'RECEPCION')
                """, destinationId, "LOC-" + destinationId.toString().substring(0, 8));
        DefaultMovementUseCase useCase = new DefaultMovementUseCase(new PostgresMovementRepository(jdbc),
                new PostgresProductRefStore(jdbc), new PostgresSupplierRefStore(jdbc), Clock.systemUTC());
        CreateMovementCommand command = new CreateMovementCommand(MovementType.ENTRY, UUID.randomUUID(), null, null,
                null, null, null, "recepción", null,
                List.of(new CreateMovementCommand.Detail(productId, BigDecimal.TEN, "BOX", null, destinationId,
                        null, null, "PEN", null, null, null, null)));

        Movement movement = useCase.createMovement(command, UUID.randomUUID());

        assertEquals(new BigDecimal("12.000000"), movement.getDetails().getFirst().getConversionFactor());
        assertEquals(new BigDecimal("120.000000"), jdbc.queryForObject(
                "SELECT stock_quantity FROM inventory.movement_detail WHERE movement_id=?", BigDecimal.class,
                movement.getId()));
    }

    @Test
    void persistsTheServerEvaluatedSensitivityReason() {
        Fixture fixture = fixture();
        DefaultMovementUseCase useCase = new DefaultMovementUseCase(new PostgresMovementRepository(jdbc),
                new PostgresProductRefStore(jdbc), new PostgresSupplierRefStore(jdbc),
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC),
                (movementId, bearerToken, correlationId) -> true);
        CreateMovementCommand command = new CreateMovementCommand(MovementType.ADJUST_NEGATIVE, null, null, null,
                null, null, null, "ajuste", null,
                List.of(new CreateMovementCommand.Detail(fixture.productId(), BigDecimal.ONE, "EA", null,
                        fixture.destinationId(), null, null, "PEN", null, null, null, null)));

        Movement draft = useCase.createMovement(command, UUID.randomUUID());
        Movement submitted = useCase.submitMovement(new utp.siga.inventory.application.command.SubmitMovementCommand(draft.getId()));

        assertEquals(MovementStatus.PENDING_AUTHORIZATION, submitted.getStatus());
        assertEquals("INVENTORY_ADJUSTMENT", new PostgresMovementRepository(jdbc).findById(draft.getId())
                .orElseThrow().getSensitivityReason());
    }

    @Test
    void listsMovementsAsARealPageAndLoadsDetailsInBatch() {
        PostgresMovementRepository repository = new PostgresMovementRepository(jdbc);
        Fixture firstFixture = fixture();
        Fixture secondFixture = fixture();
        Movement first = firstFixture.draft();
        Movement second = secondFixture.draft();
        repository.save(first);
        repository.save(second);

        MovementPage firstPage = new PostgresMovementQuery(jdbc).getMovements(0, 1);
        MovementPage secondPage = new PostgresMovementQuery(jdbc).getMovements(1, 1);

        assertEquals(1, firstPage.content().size());
        assertEquals(1, secondPage.content().size());
        assertTrue(firstPage.totalElements() >= 2);
        assertEquals(1, firstPage.content().getFirst().details().size());
        assertEquals(1, secondPage.content().getFirst().details().size());
        assertFalse(firstPage.content().getFirst().id().equals(secondPage.content().getFirst().id()));
    }

    @Test
    void movementListRejectsInvalidPagination() {
        PostgresMovementQuery query = new PostgresMovementQuery(jdbc);
        assertThrows(IllegalArgumentException.class, () -> query.getMovements(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> query.getMovements(0, 0));
        assertThrows(IllegalArgumentException.class, () -> query.getMovements(0, 101));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void allocatesUniqueFormattedCodesForConcurrentDraftCreations() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.product_ref(product_id, sku, product_type, storage_unit_code, base_unit_code)
                VALUES (?, ?, 'MATERIAL', 'EA', 'EA')
                """, productId, "SKU-" + productId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory.location(id, code, name, location_type)
                VALUES (?, ?, 'Destino concurrente', 'RECEPCION')
                """, destinationId, "LOC-" + destinationId.toString().substring(0, 8));

        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<String>> creations = java.util.stream.IntStream.range(0, 12)
                    .<Callable<String>>mapToObj(ignored -> () -> {
                        DefaultMovementUseCase useCase = new DefaultMovementUseCase(new PostgresMovementRepository(jdbc),
                                new PostgresProductRefStore(jdbc), new PostgresSupplierRefStore(jdbc),
                                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC));
                        CreateMovementCommand command = new CreateMovementCommand(MovementType.ENTRY, null, null, null,
                                null, null, null, "recepción", null,
                                List.of(new CreateMovementCommand.Detail(productId, BigDecimal.ONE, "EA", null, destinationId,
                                        null, null, "PEN", null, null, null, null)));
                        return useCase.createMovement(command, UUID.randomUUID()).getMovementCode();
                    }).toList();
            List<String> codes = executor.invokeAll(creations).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
            assertEquals(12, Set.copyOf(codes).size());
            assertTrue(codes.stream().allMatch(code -> code.matches("MOV-2026-\\d{5,}")));
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture() {
        UUID productId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory.product_ref(product_id, sku, product_type, storage_unit_code, base_unit_code)
                VALUES (?, ?, 'MATERIAL', 'EA', 'EA')
                """, productId, "SKU-" + productId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory.location(id, code, name, location_type)
                VALUES (?, ?, 'Destino', 'RECEPCION')
                """, destinationId, "LOC-" + destinationId.toString().substring(0, 8));
        return new Fixture(productId, destinationId);
    }

    private record Fixture(UUID productId, UUID destinationId) {
        Movement draft() {
            return draftBuilder().build();
        }

        Movement.Builder draftBuilder() {
            return new Movement.Builder().movementCode("MOV-2026-" + FIXTURE_CODES.incrementAndGet()).type(MovementType.ENTRY).registeredByUserId(UUID.randomUUID())
                    .addDetail(new MovementDetail(null, 1, productId, BigDecimal.TEN, "EA", BigDecimal.ONE,
                            null, destinationId, null, null, "PEN", null, null, null, null));
        }
    }
}
