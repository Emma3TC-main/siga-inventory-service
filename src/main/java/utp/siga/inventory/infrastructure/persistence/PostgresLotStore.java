package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.dto.LotAdminPage;
import utp.siga.inventory.application.dto.LotAdminView;
import utp.siga.inventory.application.dto.LotLocationQuantity;
import utp.siga.inventory.application.dto.LotStatus;
import utp.siga.inventory.application.exception.LotConflictException;
import utp.siga.inventory.application.exception.LotNotFoundException;
import utp.siga.inventory.application.port.in.LotAdminUseCase;
import utp.siga.inventory.application.port.out.LotStore;

@Repository
public class PostgresLotStore implements LotStore {
    private static final String SELECT = """
            SELECT l.id,l.product_id,l.lot_number,l.heat_number,l.manufactured_at,l.received_at,l.expires_at,
                   l.status,l.version,l.created_at,l.updated_at,
                   COALESCE(stock.total_quantity,0) AS total_quantity,
                   COALESCE(stock.locations,'[]'::jsonb)::text AS locations
            FROM inventory.lot l
            LEFT JOIN LATERAL (
              SELECT SUM(x.quantity) AS total_quantity,
                     jsonb_agg(jsonb_build_object('locationId',x.location_id,'quantity',x.quantity)
                               ORDER BY x.location_id) AS locations
              FROM (SELECT location_id,SUM(quantity) AS quantity FROM inventory.stock_balance
                    WHERE lot_id=l.id AND quantity>0 GROUP BY location_id) x
            ) stock ON true
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LotChangedEventWriter events;

    public PostgresLotStore(JdbcTemplate jdbc, ObjectMapper mapper, LotChangedEventWriter events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public LotAdminPage list(LotAdminUseCase.Filter filter, LocalDate currentDate) {
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (filter.productId() != null) { where.append(" AND l.product_id=?"); args.add(filter.productId()); }
        if (filter.status() != null) { where.append(" AND l.status=?"); args.add(filter.status().name()); }
        if (filter.expired() != null) {
            where.append(filter.expired() ? " AND l.expires_at<?" : " AND (l.expires_at IS NULL OR l.expires_at>=?)");
            args.add(currentDate);
        }
        if (filter.locationId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM inventory.stock_balance b WHERE b.lot_id=l.id AND b.location_id=? AND b.quantity>0)");
            args.add(filter.locationId());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM inventory.lot l" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.size());
        pageArgs.add((long) filter.page() * filter.size());
        List<LotAdminView> content = jdbc.query(SELECT + where
                + " ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs, currentDate), pageArgs.toArray());
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / filter.size());
        return new LotAdminPage(content, filter.page(), filter.size(), total, pages);
    }

    @Override @Transactional(readOnly = true)
    public LotAdminView get(UUID id, LocalDate currentDate) { return find(id, currentDate, false); }

    @Override
    @Transactional
    public LotAdminView create(LotAdminUseCase.Create command, LocalDate currentDate) {
        UUID id = UUID.randomUUID();
        LotStatus status = expired(command.expiresAt(), currentDate) ? LotStatus.EXPIRED : LotStatus.ACTIVE;
        try {
            jdbc.update("""
                    INSERT INTO inventory.lot(id,product_id,lot_number,heat_number,manufactured_at,received_at,
                        expires_at,status,version) VALUES (?,?,?,?,?,?,?,?,0)
                    """, id, command.productId(), command.lotNumber(), command.heatNumber(), command.manufacturedAt(),
                    command.receivedAt(), command.expiresAt(), status.name());
        } catch (DataIntegrityViolationException exception) {
            throw new LotConflictException("DATA_CONFLICT", "Lot identity already exists or is invalid");
        }
        LotAdminView after = find(id, currentDate, false);
        events.append("CREATED", command.actorUserId(), command.correlationId(), null, after);
        return after;
    }

    @Override
    @Transactional
    public LotAdminView update(LotAdminUseCase.Update command, LocalDate currentDate) {
        LotAdminView before = find(command.id(), currentDate, true);
        requireVersion(before.version(), command.expectedVersion());
        LotStatus status = correctedStatus(before.status(), command.expiresAt(), currentDate);
        if (Objects.equals(before.manufacturedAt(), command.manufacturedAt())
                && Objects.equals(before.receivedAt(), command.receivedAt())
                && Objects.equals(before.expiresAt(), command.expiresAt()) && before.status() == status) return before;
        int changed = jdbc.update("""
                UPDATE inventory.lot SET manufactured_at=?,received_at=?,expires_at=?,status=?,
                    version=version+1,updated_at=now() WHERE id=? AND version=?
                """, command.manufacturedAt(), command.receivedAt(), command.expiresAt(), status.name(),
                command.id(), command.expectedVersion());
        if (changed != 1) throw versionConflict();
        LotAdminView after = find(command.id(), currentDate, false);
        events.append(before.status() == after.status() ? "UPDATED" : "STATUS_CHANGED",
                command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    @Override
    @Transactional
    public LotAdminView changeStatus(LotAdminUseCase.ChangeStatus command, LocalDate currentDate) {
        LotAdminView before = find(command.id(), currentDate, true);
        requireVersion(before.version(), command.expectedVersion());
        if (before.status() == command.targetStatus()) {
            if (command.targetStatus() == LotStatus.ACTIVE && before.expired()) throw statusConflict();
            return before;
        }
        if (!allowed(before, command.targetStatus())) throw statusConflict();
        if (command.targetStatus() == LotStatus.ACTIVE && before.expired()) throw statusConflict();
        if (before.status() == LotStatus.DEPLETED && before.totalQuantity().signum() != 0) throw statusConflict();
        int changed = jdbc.update("UPDATE inventory.lot SET status=?,version=version+1,updated_at=now() "
                + "WHERE id=? AND version=?", command.targetStatus().name(), command.id(), command.expectedVersion());
        if (changed != 1) throw versionConflict();
        LotAdminView after = find(command.id(), currentDate, false);
        events.append("STATUS_CHANGED", command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    private LotAdminView find(UUID id, LocalDate currentDate, boolean lock) {
        String sql = lock
                ? "SELECT id FROM inventory.lot WHERE id=? FOR UPDATE"
                : null;
        if (lock && jdbc.queryForList(sql, UUID.class, id).isEmpty()) throw new LotNotFoundException();
        List<LotAdminView> rows = jdbc.query(SELECT + " WHERE l.id=?", (rs, row) -> map(rs, currentDate), id);
        if (rows.isEmpty()) throw new LotNotFoundException();
        return rows.getFirst();
    }

    private LotAdminView map(ResultSet rs, LocalDate currentDate) throws SQLException {
        return new LotAdminView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("lot_number"), rs.getString("heat_number"),
                rs.getObject("manufactured_at", LocalDate.class), rs.getObject("received_at", LocalDate.class),
                rs.getObject("expires_at", LocalDate.class), LotStatus.valueOf(rs.getString("status")),
                expired(rs.getObject("expires_at", LocalDate.class), currentDate), rs.getBigDecimal("total_quantity"),
                locations(rs.getString("locations")), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private List<LotLocationQuantity> locations(String json) {
        try { return mapper.readValue(json, new TypeReference<List<LotLocationQuantity>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not read lot locations", exception); }
    }

    private static LotStatus correctedStatus(LotStatus current, LocalDate expiry, LocalDate today) {
        if (current == LotStatus.ACTIVE && expired(expiry, today)) return LotStatus.EXPIRED;
        if (current == LotStatus.EXPIRED && !expired(expiry, today)) return LotStatus.ACTIVE;
        return current;
    }

    private static boolean allowed(LotAdminView before, LotStatus target) {
        return switch (before.status()) {
            case ACTIVE -> target == LotStatus.QUARANTINED || target == LotStatus.BLOCKED;
            case QUARANTINED -> target == LotStatus.BLOCKED || target == LotStatus.ACTIVE;
            case BLOCKED -> target == LotStatus.QUARANTINED || target == LotStatus.ACTIVE;
            case DEPLETED -> target == LotStatus.ACTIVE;
            case EXPIRED -> false;
        };
    }

    private static boolean expired(LocalDate expiry, LocalDate today) {
        return expiry != null && expiry.isBefore(today);
    }
    private static void requireVersion(long actual, long expected) {
        if (actual != expected) throw versionConflict();
    }
    private static LotConflictException versionConflict() {
        return new LotConflictException("VERSION_CONFLICT", "Lot version does not match");
    }
    private static LotConflictException statusConflict() {
        return new LotConflictException("LOT_STATUS_CONFLICT", "Lot status transition is not allowed");
    }
}
