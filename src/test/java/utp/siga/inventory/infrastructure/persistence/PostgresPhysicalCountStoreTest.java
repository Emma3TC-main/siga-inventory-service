package utp.siga.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import utp.siga.inventory.application.exception.PhysicalCountConflictException;
import utp.siga.inventory.application.exception.PhysicalCountValidationException;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.domain.model.PhysicalCount;
import utp.siga.inventory.domain.model.PhysicalCountStatus;

@JdbcTest
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostgresPhysicalCountStore.class, PostgresPhysicalCountStoreTest.Dependencies.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresPhysicalCountStoreTest {
    private static final UUID ACTOR = UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final UUID STOCK_LOCATION = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID EMPTY_LOCATION = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_WITH_LOT = UUID.fromString("22000000-0000-0000-0000-000000000002");
    private static final UUID LOT = UUID.fromString("31000000-0000-0000-0000-000000000002");
    private static final UUID SERIAL_PRODUCT = UUID.fromString("22000000-0000-0000-0000-000000000004");
    private static final UUID ASSET = UUID.fromString("32000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_LOCATION = UUID.fromString("30000000-0000-0000-0000-000000000005");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_inventory_physical_count_test")
            .withUsername("siga_inventory_physical_count_test")
            .withPassword("physical-count-test-only");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("inventory.test.expected-database", POSTGRES::getDatabaseName);
        registry.add("inventory.test.expected-user", POSTGRES::getUsername);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.schemas", () -> "inventory");
        registry.add("spring.flyway.default-schema", () -> "inventory");
    }

    @Autowired PostgresPhysicalCountStore store;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeOnlyThisClassFixtures() {
        jdbc.update("DELETE FROM inventory.physical_count_line");
        jdbc.update("DELETE FROM inventory.physical_count");
        jdbc.update("DELETE FROM inventory.idempotency_record WHERE resource_type LIKE 'PHYSICAL_COUNT%'");
        jdbc.update("UPDATE inventory.stock_balance SET quantity=2500 WHERE id="
                + "'33000000-0000-0000-0000-000000000001'");
        jdbc.update("UPDATE inventory.asset SET operational_status='OPERATIVO' WHERE id=?", ASSET);
    }

    @Test
    void snapshotsAllDimensionsAndLaterStockChangesNeverRewriteSnapshotOrCloseStock() {
        PhysicalCount count = create(STOCK_LOCATION, UUID.randomUUID());
        assertThat(count.status()).isEqualTo(PhysicalCountStatus.DRAFT);
        assertThat(count.lines()).hasSize(2);
        assertThat(quantity(count, "22000000-0000-0000-0000-000000000001"))
                .isEqualByComparingTo("2500");

        jdbc.update("UPDATE inventory.stock_balance SET quantity=2600 WHERE id="
                + "'33000000-0000-0000-0000-000000000001'");
        long version = count.version();
        for (var line : count.lines()) {
            count = store.capture(new PhysicalCountUseCase.Capture(count.id(), line.id(), version++,
                    line.systemQuantity(), "captured", ACTOR));
        }
        count = store.close(new PhysicalCountUseCase.Command(count.id(), count.version(), ACTOR, UUID.randomUUID()));

        assertThat(count.status()).isEqualTo(PhysicalCountStatus.COMPLETED);
        assertThat(quantity(count, "22000000-0000-0000-0000-000000000001"))
                .isEqualByComparingTo("2500");
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory.stock_balance WHERE id="
                + "'33000000-0000-0000-0000-000000000001'", BigDecimal.class))
                .isEqualByComparingTo("2600");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movement", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event", Long.class)).isZero();
    }

    @Test
    void snapshotsRestrictedAssetBecausePhysicalCountRecordsPhysicalReality() {
        jdbc.update("UPDATE inventory.asset SET operational_status='INOPERATIVO' WHERE id=?", ASSET);

        PhysicalCount count = create(ASSET_LOCATION, UUID.randomUUID());

        assertThat(count.lines()).anySatisfy(line -> {
            assertThat(line.productId()).isEqualTo(SERIAL_PRODUCT);
            assertThat(line.assetId()).isEqualTo(ASSET);
            assertThat(line.systemQuantity()).isEqualByComparingTo(BigDecimal.ONE);
        });
    }

    @Test
    void supportsPersistentIdempotentCreateCloseCancelAndRejectsDifferentRequest() {
        UUID createKey = UUID.randomUUID();
        PhysicalCount created = create(EMPTY_LOCATION, createKey);
        PhysicalCount replay = create(EMPTY_LOCATION, createKey);
        assertThat(replay).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.physical_count", Long.class)).isOne();
        assertThatThrownBy(() -> store.create(new PhysicalCountUseCase.Create(
                STOCK_LOCATION, "different", ACTOR, createKey)))
                .isInstanceOf(PhysicalCountConflictException.class)
                .extracting(exception -> ((PhysicalCountConflictException) exception).code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        UUID closeKey = UUID.randomUUID();
        PhysicalCount closed = store.close(new PhysicalCountUseCase.Command(
                created.id(), created.version(), ACTOR, closeKey));
        assertThat(store.close(new PhysicalCountUseCase.Command(
                created.id(), created.version(), ACTOR, closeKey))).isEqualTo(closed);

        PhysicalCount cancellable = create(EMPTY_LOCATION, UUID.randomUUID());
        UUID cancelKey = UUID.randomUUID();
        PhysicalCount cancelled = store.cancel(new PhysicalCountUseCase.Cancel(
                cancellable.id(), 0, "recount", ACTOR, cancelKey));
        assertThat(store.cancel(new PhysicalCountUseCase.Cancel(
                cancellable.id(), 0, "recount", ACTOR, cancelKey))).isEqualTo(cancelled);
    }

    @Test
    void addsUnexpectedDimensionOnceAndEnforcesAssetQuantity() {
        PhysicalCount count = create(EMPTY_LOCATION, UUID.randomUUID());
        UUID addKey = UUID.randomUUID();
        var addCommand = new PhysicalCountUseCase.AddUnexpected(
                count.id(), 0, PRODUCT_WITH_LOT, LOT, null, new BigDecimal("3.00"),
                "found", ACTOR, addKey);
        PhysicalCount added = store.addUnexpected(addCommand);
        assertThat(store.addUnexpected(addCommand)).isEqualTo(added);
        assertThat(added.status()).isEqualTo(PhysicalCountStatus.IN_PROGRESS);
        assertThat(added.lines()).singleElement().satisfies(line -> {
            assertThat(line.systemQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(line.differenceQuantity()).isEqualByComparingTo("3");
        });
        assertThatThrownBy(() -> store.addUnexpected(new PhysicalCountUseCase.AddUnexpected(
                count.id(), added.version(), PRODUCT_WITH_LOT, LOT, null, BigDecimal.ONE,
                null, ACTOR, UUID.randomUUID())))
                .isInstanceOf(PhysicalCountConflictException.class);

        PhysicalCount assetCount = create(EMPTY_LOCATION, UUID.randomUUID());
        assertThatThrownBy(() -> store.addUnexpected(new PhysicalCountUseCase.AddUnexpected(
                assetCount.id(), 0, SERIAL_PRODUCT, null, ASSET, new BigDecimal("2"),
                null, ACTOR, UUID.randomUUID())))
                .isInstanceOf(PhysicalCountValidationException.class);
    }

    @Test
    void staleAndConcurrentUpdatesAllowExactlyOneVersionWinner() throws Exception {
        PhysicalCount count = create(STOCK_LOCATION, UUID.randomUUID());
        List<UUID> lineIds = count.lines().stream().map(line -> line.id()).toList();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> captureAfter(start, count.id(), lineIds.get(0)));
            Future<Object> second = executor.submit(() -> captureAfter(start, count.id(), lineIds.get(1)));
            start.countDown();
            List<Object> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(PhysicalCount.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(PhysicalCountConflictException.class::isInstance)).hasSize(1);
            assertThat(store.get(count.id()).version()).isOne();
            assertThat(store.get(count.id()).lines().stream().filter(line -> line.countedQuantity() != null)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentUnexpectedDimensionCreatesExactlyOneRow() throws Exception {
        PhysicalCount count = create(EMPTY_LOCATION, UUID.randomUUID());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> addAfter(start, count.id()));
            Future<Object> second = executor.submit(() -> addAfter(start, count.id()));
            start.countDown();
            List<Object> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(PhysicalCount.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(PhysicalCountConflictException.class::isInstance)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.physical_count_line WHERE count_id=?",
                    Long.class, count.id())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void listUsesStableDatabasePaginationAndEmptyDraftCanClose() {
        PhysicalCount first = create(EMPTY_LOCATION, UUID.randomUUID());
        PhysicalCount second = create(EMPTY_LOCATION, UUID.randomUUID());
        var page = store.list(0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().id()).isIn(first.id(), second.id());
        assertThat(store.close(new PhysicalCountUseCase.Command(first.id(), 0, ACTOR, UUID.randomUUID())).status())
                .isEqualTo(PhysicalCountStatus.COMPLETED);
    }

    private Object captureAfter(CountDownLatch start, UUID countId, UUID lineId) {
        try {
            start.await();
            return store.capture(new PhysicalCountUseCase.Capture(countId, lineId, 0,
                    BigDecimal.ONE, null, ACTOR));
        } catch (PhysicalCountConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Object addAfter(CountDownLatch start, UUID countId) {
        try {
            start.await();
            return store.addUnexpected(new PhysicalCountUseCase.AddUnexpected(countId, 0,
                    PRODUCT_WITH_LOT, LOT, null, BigDecimal.ONE, null, ACTOR, UUID.randomUUID()));
        } catch (PhysicalCountConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private PhysicalCount create(UUID location, UUID key) {
        return store.create(new PhysicalCountUseCase.Create(location, "cycle", ACTOR, key));
    }

    private BigDecimal quantity(PhysicalCount count, String productId) {
        UUID product = UUID.fromString(productId);
        return count.lines().stream().filter(line -> line.productId().equals(product)).findFirst().orElseThrow()
                .systemQuantity();
    }

    static class Dependencies {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
