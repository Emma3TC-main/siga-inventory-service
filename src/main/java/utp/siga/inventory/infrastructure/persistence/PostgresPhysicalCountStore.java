package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.dto.PhysicalCountPage;
import utp.siga.inventory.application.exception.PhysicalCountConflictException;
import utp.siga.inventory.application.exception.PhysicalCountNotFoundException;
import utp.siga.inventory.application.exception.PhysicalCountValidationException;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.application.port.out.PhysicalCountStore;
import utp.siga.inventory.domain.model.PhysicalCount;
import utp.siga.inventory.domain.model.PhysicalCountLine;
import utp.siga.inventory.domain.model.PhysicalCountStatus;

@Repository
public class PostgresPhysicalCountStore implements PhysicalCountStore {
    private static final String HEADER_COLUMNS = """
            c.id, c.location_id, c.status, c.version, c.notes, c.created_by_user_id,
            c.started_at, c.completed_by_user_id, c.completed_at, c.cancelled_by_user_id,
            c.cancelled_at, c.cancellation_reason, c.created_at, c.updated_at
            """;
    private static final String LINE_COLUMNS = """
            l.id AS line_id, l.product_id, l.lot_id, l.asset_id, l.system_quantity,
            l.counted_quantity, l.notes AS line_notes, l.counted_by_user_id, l.counted_at
            """;
    private static final String DETAIL_QUERY = "SELECT " + HEADER_COLUMNS + ", " + LINE_COLUMNS + """
            FROM inventory.physical_count c
            LEFT JOIN inventory.physical_count_line l ON l.count_id=c.id
            WHERE c.id=?
            ORDER BY l.id ASC
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PostgresPhysicalCountStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PhysicalCountPage list(int page, int size) {
        long total = jdbc.queryForObject("SELECT count(*) FROM inventory.physical_count", Long.class);
        String sql = """
                WITH selected AS (
                    SELECT id FROM inventory.physical_count
                    ORDER BY created_at DESC, id DESC
                    LIMIT ? OFFSET ?
                )
                SELECT %s, %s
                FROM selected s
                JOIN inventory.physical_count c ON c.id=s.id
                LEFT JOIN inventory.physical_count_line l ON l.count_id=c.id
                ORDER BY c.created_at DESC, c.id DESC, l.id ASC
                """.formatted(HEADER_COLUMNS, LINE_COLUMNS);
        List<PhysicalCount> content = jdbc.query(sql, this::extractCounts, size,
                Math.multiplyExact((long) page, (long) size));
        return new PhysicalCountPage(content, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public PhysicalCount get(UUID id) {
        return find(id);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhysicalCount create(PhysicalCountUseCase.Create command) {
        LocationLocks.lockHierarchy(jdbc);
        String hash = hash("CREATE", command.locationId(), command.notes());
        UUID countId = UUID.randomUUID();
        Idempotency claim = claim(command.idempotencyKey(), hash, "PHYSICAL_COUNT_CREATE", countId, 201);
        if (claim.replay()) return replay(claim);

        if (!Boolean.TRUE.equals(jdbc.query("SELECT active FROM inventory.location WHERE id=? FOR KEY SHARE",
                result -> result.next() ? result.getBoolean(1) : null, command.locationId()))) {
            throw new PhysicalCountNotFoundException("LOCATION_NOT_FOUND", "Active location not found");
        }
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO inventory.physical_count(
                    id, location_id, status, created_by_user_id, notes, started_at, created_at, updated_at, version)
                VALUES (?, ?, 'DRAFT', ?, ?, NULL, ?, ?, 0)
                """, countId, command.locationId(), command.actorUserId(), command.notes(), timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO inventory.physical_count_line(
                    id, count_id, product_id, lot_id, asset_id, system_quantity, counted_quantity)
                SELECT gen_random_uuid(), ?, product_id, lot_id, asset_id, quantity, NULL
                FROM inventory.stock_balance
                WHERE location_id=?
                ORDER BY product_id, lot_id NULLS FIRST, asset_id NULLS FIRST
                """, countId, command.locationId());
        PhysicalCount created = find(countId);
        complete(command.idempotencyKey(), created);
        return created;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhysicalCount capture(PhysicalCountUseCase.Capture command) {
        requireQuantity(command.countedQuantity());
        PhysicalCount count = find(command.countId());
        count.requireVersion(command.expectedVersion());
        count.requireOpen();
        PhysicalCountLine line = count.lines().stream().filter(item -> item.id().equals(command.lineId()))
                .findFirst().orElseThrow(() -> new PhysicalCountNotFoundException(
                        "PHYSICAL_COUNT_LINE_NOT_FOUND", "Physical count line not found"));
        if (line.assetId() != null) requireAssetQuantity(command.countedQuantity());

        Instant now = clock.instant();
        bump(count, count.statusAfterCapture(), now);
        jdbc.update("""
                UPDATE inventory.physical_count_line
                SET counted_quantity=?, notes=?, counted_by_user_id=?, counted_at=?
                WHERE id=? AND count_id=?
                """, command.countedQuantity(), command.notes(), command.actorUserId(), timestamp(now),
                command.lineId(), command.countId());
        return find(command.countId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhysicalCount addUnexpected(PhysicalCountUseCase.AddUnexpected command) {
        requireQuantity(command.countedQuantity());
        String hash = hash("ADD_LINE", command.countId(), command.expectedVersion(), command.productId(),
                command.lotId(), command.assetId(), normalized(command.countedQuantity()), command.notes());
        Idempotency claim = claim(command.idempotencyKey(), hash, "PHYSICAL_COUNT_LINE", command.countId(), 200);
        if (claim.replay()) return replay(claim);

        PhysicalCount count = find(command.countId());
        count.requireVersion(command.expectedVersion());
        count.requireOpen();
        validateDimension(command.productId(), command.lotId(), command.assetId());
        if (command.assetId() != null) requireAssetQuantity(command.countedQuantity());

        Instant now = clock.instant();
        bump(count, count.statusAfterCapture(), now);
        try {
            jdbc.update("""
                    INSERT INTO inventory.physical_count_line(
                        id, count_id, product_id, lot_id, asset_id, system_quantity, counted_quantity,
                        notes, counted_by_user_id, counted_at)
                    VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                    """, UUID.randomUUID(), command.countId(), command.productId(), command.lotId(),
                    command.assetId(), command.countedQuantity(), command.notes(), command.actorUserId(), timestamp(now));
        } catch (DataIntegrityViolationException exception) {
            throw new PhysicalCountConflictException("PHYSICAL_COUNT_DIMENSION_CONFLICT",
                    "Physical count dimension already exists or is inconsistent");
        }
        PhysicalCount result = find(command.countId());
        complete(command.idempotencyKey(), result);
        return result;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhysicalCount close(PhysicalCountUseCase.Command command) {
        String hash = hash("CLOSE", command.countId(), command.expectedVersion());
        Idempotency claim = claim(command.idempotencyKey(), hash, "PHYSICAL_COUNT_CLOSE", command.countId(), 200);
        if (claim.replay()) return replay(claim);

        PhysicalCount count = find(command.countId());
        count.requireVersion(command.expectedVersion());
        count.validateClose();
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE inventory.physical_count
                SET status='COMPLETED', completed_by_user_id=?, completed_at=?, version=version+1, updated_at=?
                WHERE id=? AND version=?
                """, command.actorUserId(), timestamp(now), timestamp(now), command.countId(), command.expectedVersion());
        requireVersionUpdate(updated);
        PhysicalCount result = find(command.countId());
        complete(command.idempotencyKey(), result);
        return result;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhysicalCount cancel(PhysicalCountUseCase.Cancel command) {
        String hash = hash("CANCEL", command.countId(), command.expectedVersion(), command.reason());
        Idempotency claim = claim(command.idempotencyKey(), hash, "PHYSICAL_COUNT_CANCEL", command.countId(), 200);
        if (claim.replay()) return replay(claim);

        PhysicalCount count = find(command.countId());
        count.requireVersion(command.expectedVersion());
        count.validateCancellationReason(command.reason());
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE inventory.physical_count
                SET status='CANCELLED', cancelled_by_user_id=?, cancelled_at=?, cancellation_reason=?,
                    version=version+1, updated_at=?
                WHERE id=? AND version=?
                """, command.actorUserId(), timestamp(now), command.reason().trim(), timestamp(now),
                command.countId(), command.expectedVersion());
        requireVersionUpdate(updated);
        PhysicalCount result = find(command.countId());
        complete(command.idempotencyKey(), result);
        return result;
    }

    private void bump(PhysicalCount count, PhysicalCountStatus status, Instant now) {
        int updated = jdbc.update("""
                UPDATE inventory.physical_count
                SET status=?, started_at=COALESCE(started_at, ?), version=version+1, updated_at=?
                WHERE id=? AND version=?
                """, status.name(), timestamp(now), timestamp(now), count.id(), count.version());
        requireVersionUpdate(updated);
    }

    private void requireVersionUpdate(int updated) {
        if (updated != 1) {
            throw new PhysicalCountConflictException("VERSION_CONFLICT", "Physical count version is stale");
        }
    }

    private void validateDimension(UUID productId, UUID lotId, UUID assetId) {
        Map<String, Object> product = jdbc.query("""
                SELECT requires_lot, requires_serial FROM inventory.product_ref WHERE product_id=?
                """, result -> {
                    if (!result.next()) return null;
                    return Map.of("lot", result.getBoolean("requires_lot"),
                            "serial", result.getBoolean("requires_serial"));
                }, productId);
        if (product == null) {
            throw new PhysicalCountNotFoundException("REFERENCE_NOT_FOUND", "Product reference not found");
        }
        if (Boolean.TRUE.equals(product.get("lot")) && lotId == null) {
            throw new PhysicalCountValidationException("VALIDATION_ERROR", "lotId is required for this product");
        }
        if (Boolean.TRUE.equals(product.get("serial")) && assetId == null) {
            throw new PhysicalCountValidationException("VALIDATION_ERROR", "assetId is required for this product");
        }
        if (lotId != null && jdbc.queryForObject(
                "SELECT count(*) FROM inventory.lot WHERE id=? AND product_id=?", Integer.class, lotId, productId) != 1) {
            throw new PhysicalCountNotFoundException("REFERENCE_NOT_FOUND", "Lot reference not found for product");
        }
        if (assetId != null && jdbc.queryForObject(
                "SELECT count(*) FROM inventory.asset WHERE id=? AND product_id=?", Integer.class, assetId, productId) != 1) {
            throw new PhysicalCountNotFoundException("REFERENCE_NOT_FOUND", "Asset reference not found for product");
        }
    }

    private Idempotency claim(UUID key, String requestHash, String resourceType, UUID resourceId, int status) {
        Instant now = clock.instant();
        int inserted = jdbc.update("""
                INSERT INTO inventory.idempotency_record(
                    idempotency_key, request_hash, resource_type, resource_id, response_status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, requestHash, resourceType, resourceId, status,
                timestamp(now.plus(30, ChronoUnit.DAYS)));
        if (inserted == 1) return new Idempotency(false, requestHash, resourceType, resourceId, null);
        return jdbc.query("""
                SELECT request_hash, resource_type, resource_id, response_body::text
                FROM inventory.idempotency_record WHERE idempotency_key=?
                """, result -> {
                    if (!result.next()) throw new IllegalStateException("Idempotency claim disappeared");
                    String existingHash = result.getString("request_hash");
                    String existingType = result.getString("resource_type");
                    if (!requestHash.equals(existingHash) || !resourceType.equals(existingType)) {
                        throw new PhysicalCountConflictException("IDEMPOTENCY_CONFLICT",
                                "Idempotency-Key was already used for another request");
                    }
                    String body = result.getString("response_body");
                    if (body == null) throw new IllegalStateException("Idempotent request is incomplete");
                    return new Idempotency(true, existingHash, existingType,
                            result.getObject("resource_id", UUID.class), body);
                }, key);
    }

    private void complete(UUID key, PhysicalCount response) {
        try {
            jdbc.update("UPDATE inventory.idempotency_record SET response_body=?::jsonb WHERE idempotency_key=?",
                    mapper.writeValueAsString(response), key);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not persist idempotent physical count response", exception);
        }
    }

    private PhysicalCount replay(Idempotency claim) {
        try {
            return mapper.readValue(claim.responseBody(), PhysicalCount.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read idempotent physical count response", exception);
        }
    }

    private PhysicalCount find(UUID id) {
        List<PhysicalCount> counts = jdbc.query(DETAIL_QUERY, this::extractCounts, id);
        if (counts.isEmpty()) {
            throw new PhysicalCountNotFoundException("PHYSICAL_COUNT_NOT_FOUND", "Physical count not found");
        }
        return counts.getFirst();
    }

    private List<PhysicalCount> extractCounts(ResultSet result) throws SQLException {
        Map<UUID, CountRows> counts = new LinkedHashMap<>();
        while (result.next()) {
            UUID countId = result.getObject("id", UUID.class);
            CountRows rows = counts.computeIfAbsent(countId, ignored -> new CountRows(mapHeader(result)));
            UUID lineId = result.getObject("line_id", UUID.class);
            if (lineId != null) rows.lines.add(mapLine(result, lineId));
        }
        return counts.values().stream().map(CountRows::build).toList();
    }

    private PhysicalCount mapHeader(ResultSet result) {
        try {
            return new PhysicalCount(result.getObject("id", UUID.class),
                    result.getObject("location_id", UUID.class),
                    PhysicalCountStatus.valueOf(result.getString("status")), result.getLong("version"),
                    result.getString("notes"), result.getObject("created_by_user_id", UUID.class),
                    instant(result, "started_at"), result.getObject("completed_by_user_id", UUID.class),
                    instant(result, "completed_at"), result.getObject("cancelled_by_user_id", UUID.class),
                    instant(result, "cancelled_at"), result.getString("cancellation_reason"),
                    instant(result, "created_at"), instant(result, "updated_at"), List.of());
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PhysicalCountLine mapLine(ResultSet result, UUID lineId) throws SQLException {
        return new PhysicalCountLine(lineId, result.getObject("product_id", UUID.class),
                result.getObject("lot_id", UUID.class), result.getObject("asset_id", UUID.class),
                result.getBigDecimal("system_quantity"), result.getBigDecimal("counted_quantity"),
                result.getString("line_notes"), result.getObject("counted_by_user_id", UUID.class),
                instant(result, "counted_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static void requireQuantity(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new PhysicalCountValidationException("VALIDATION_ERROR", "countedQuantity must be non-negative");
        }
    }

    private static void requireAssetQuantity(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) != 0 && value.compareTo(BigDecimal.ONE) != 0) {
            throw new PhysicalCountValidationException("VALIDATION_ERROR",
                    "Serialized asset countedQuantity must be zero or one");
        }
    }

    private static String hash(Object... parts) {
        StringBuilder value = new StringBuilder();
        for (Object part : parts) value.append(part == null ? "<null>" : part).append('\u001f');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object normalized(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return value;
    }

    private record Idempotency(boolean replay, String requestHash, String resourceType,
            UUID resourceId, String responseBody) { }

    private static final class CountRows {
        private final PhysicalCount header;
        private final List<PhysicalCountLine> lines = new ArrayList<>();

        private CountRows(PhysicalCount header) { this.header = header; }

        private PhysicalCount build() {
            return new PhysicalCount(header.id(), header.locationId(), header.status(), header.version(),
                    header.notes(), header.createdByUserId(), header.startedAt(), header.completedByUserId(),
                    header.completedAt(), header.cancelledByUserId(), header.cancelledAt(),
                    header.cancellationReason(), header.createdAt(), header.updatedAt(), lines);
        }
    }
}
