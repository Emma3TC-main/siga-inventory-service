package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.dto.AssetAdminPage;
import utp.siga.inventory.application.dto.AssetAdminView;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.exception.AssetConflictException;
import utp.siga.inventory.application.exception.AssetNotFoundException;
import utp.siga.inventory.application.port.in.AssetAdminUseCase;
import utp.siga.inventory.application.port.out.AssetStore;

@Repository
public class PostgresAssetStore implements AssetStore {
    private static final String SELECT = """
            SELECT a.id,a.product_id,a.asset_code,a.serial_number,a.brand,a.model,a.operational_status,
                   a.technical_snapshot::text AS technical_snapshot,a.version,a.created_at,a.updated_at,
                   stock.balance_count,stock.current_location_id,stock.current_lot_id,stock.unit_quantities
            FROM inventory.asset a
            LEFT JOIN LATERAL (
              SELECT count(*) AS balance_count,
                     (array_agg(location_id ORDER BY location_id))[1] AS current_location_id,
                     (array_agg(lot_id ORDER BY location_id))[1] AS current_lot_id,
                     bool_and(quantity=1) AS unit_quantities
              FROM inventory.stock_balance
              WHERE asset_id=a.id AND quantity>0
            ) stock ON true
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssetChangedEventWriter events;

    public PostgresAssetStore(JdbcTemplate jdbc, ObjectMapper mapper, AssetChangedEventWriter events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public AssetAdminPage list(AssetAdminUseCase.Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (filter.productId() != null) { where.append(" AND a.product_id=?"); args.add(filter.productId()); }
        if (filter.operationalStatus() != null) {
            where.append(" AND a.operational_status=?"); args.add(filter.operationalStatus().name());
        }
        if (filter.availability() == AssetAvailability.UNASSIGNED) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM inventory.stock_balance b WHERE b.asset_id=a.id AND b.quantity>0)");
        } else if (filter.availability() == AssetAvailability.IN_STOCK) {
            where.append(" AND EXISTS (SELECT 1 FROM inventory.stock_balance b WHERE b.asset_id=a.id AND b.quantity=1)");
        }
        if (filter.locationId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM inventory.stock_balance b WHERE b.asset_id=a.id AND b.location_id=? AND b.quantity>0)");
            args.add(filter.locationId());
        }
        if (filter.lotId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM inventory.stock_balance b WHERE b.asset_id=a.id AND b.lot_id=? AND b.quantity>0)");
            args.add(filter.lotId());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM inventory.asset a" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.size());
        pageArgs.add((long) filter.page() * filter.size());
        List<AssetAdminView> content = jdbc.query(SELECT + where
                + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?", this::map, pageArgs.toArray());
        return new AssetAdminPage(content, filter.page(), filter.size(), total == null ? 0 : total);
    }

    @Override @Transactional(readOnly = true)
    public AssetAdminView get(UUID id) { return find(id, false); }

    @Override
    @Transactional
    public AssetAdminView register(AssetAdminUseCase.Register command) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO inventory.asset(id,product_id,asset_code,serial_number,brand,model,
                        operational_status,technical_snapshot,version)
                    VALUES (?,?,?,?,?,?,'OPERATIVO',?::jsonb,0)
                    """, id, command.productId(), command.assetCode(), command.serialNumber(), command.brand(),
                    command.model(), json(command.technicalSnapshot()));
        } catch (DataIntegrityViolationException exception) {
            throw new AssetConflictException("DATA_CONFLICT", "Asset code or serial number already exists");
        }
        AssetAdminView after = find(id, false);
        events.append("REGISTERED", command.actorUserId(), command.correlationId(), null, after);
        return after;
    }

    @Override
    @Transactional
    public AssetAdminView update(AssetAdminUseCase.Update command) {
        AssetAdminView before = find(command.id(), true);
        requireVersion(before.version(), command.expectedVersion());
        if (Objects.equals(before.brand(), command.brand()) && Objects.equals(before.model(), command.model())
                && before.technicalSnapshot().equals(command.technicalSnapshot())) return before;
        int changed = jdbc.update("""
                UPDATE inventory.asset SET brand=?,model=?,technical_snapshot=?::jsonb,
                    version=version+1,updated_at=now() WHERE id=? AND version=?
                """, command.brand(), command.model(), json(command.technicalSnapshot()),
                command.id(), command.expectedVersion());
        if (changed != 1) throw versionConflict();
        AssetAdminView after = find(command.id(), false);
        events.append("UPDATED", command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    @Override
    @Transactional
    public AssetAdminView changeStatus(AssetAdminUseCase.ChangeStatus command) {
        AssetAdminView before = find(command.id(), true);
        requireVersion(before.version(), command.expectedVersion());
        if (before.operationalStatus() == command.targetStatus()) return before;
        if (!allowed(before.operationalStatus(), command.targetStatus())) throw statusConflict();
        if (command.targetStatus() == AssetOperationalStatus.FUERA_SERVICIO && hasPositiveStock(command.id())) {
            throw statusConflict();
        }
        int changed = jdbc.update("UPDATE inventory.asset SET operational_status=?,version=version+1,updated_at=now() "
                + "WHERE id=? AND version=?", command.targetStatus().name(), command.id(), command.expectedVersion());
        if (changed != 1) throw versionConflict();
        AssetAdminView after = find(command.id(), false);
        events.append("STATUS_CHANGED", command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    private boolean hasPositiveStock(UUID id) {
        return !jdbc.queryForList("SELECT id FROM inventory.stock_balance WHERE asset_id=? AND quantity>0 "
                + "ORDER BY id FOR UPDATE", UUID.class, id).isEmpty();
    }

    private AssetAdminView find(UUID id, boolean lock) {
        if (lock && jdbc.queryForList("SELECT id FROM inventory.asset WHERE id=? FOR UPDATE", UUID.class, id).isEmpty()) {
            throw new AssetNotFoundException();
        }
        List<AssetAdminView> rows = jdbc.query(SELECT + " WHERE a.id=?", this::map, id);
        if (rows.isEmpty()) throw new AssetNotFoundException();
        return rows.getFirst();
    }

    private AssetAdminView map(ResultSet rs, int row) throws SQLException {
        long balances = rs.getLong("balance_count");
        if (balances > 1 || (balances == 1 && !rs.getBoolean("unit_quantities"))) {
            throw new IllegalStateException("Asset has an invalid positive stock distribution");
        }
        return new AssetAdminView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("asset_code"), rs.getString("serial_number"), rs.getString("brand"),
                rs.getString("model"), AssetOperationalStatus.valueOf(rs.getString("operational_status")),
                readJson(rs.getString("technical_snapshot")), rs.getObject("current_location_id", UUID.class),
                rs.getObject("current_lot_id", UUID.class), balances == 1, rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private JsonNode readJson(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not read asset metadata", exception); }
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Invalid technicalSnapshot", exception); }
    }

    private static boolean allowed(AssetOperationalStatus current, AssetOperationalStatus target) {
        return switch (current) {
            case OPERATIVO -> target == AssetOperationalStatus.EN_MANTENIMIENTO
                    || target == AssetOperationalStatus.INOPERATIVO;
            case EN_MANTENIMIENTO -> target == AssetOperationalStatus.OPERATIVO
                    || target == AssetOperationalStatus.FUERA_SERVICIO;
            case INOPERATIVO -> target == AssetOperationalStatus.EN_MANTENIMIENTO
                    || target == AssetOperationalStatus.FUERA_SERVICIO;
            case EN_TRANSITO, FUERA_SERVICIO -> false;
        };
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) throw versionConflict();
    }
    private static AssetConflictException versionConflict() {
        return new AssetConflictException("VERSION_CONFLICT", "Asset version does not match");
    }
    private static AssetConflictException statusConflict() {
        return new AssetConflictException("ASSET_STATUS_CONFLICT", "Asset status transition is not allowed");
    }
}
