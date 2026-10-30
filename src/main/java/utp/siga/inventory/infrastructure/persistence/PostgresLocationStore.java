package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.exception.LocationConflictException;
import utp.siga.inventory.application.exception.LocationNotFoundException;
import utp.siga.inventory.application.port.in.LocationUseCase;
import utp.siga.inventory.application.port.out.LocationStore;
import utp.siga.inventory.application.port.out.LocationActivityPort;

@Repository
public class PostgresLocationStore implements LocationStore, LocationActivityPort {
    private static final String COLUMNS = "id,code,name,location_type,parent_id,active,version";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PostgresLocationStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationView> list(LocationActiveFilter active) {
        String predicate = switch (active) {
            case TRUE -> " WHERE active=true";
            case FALSE -> " WHERE active=false";
            case ALL -> "";
        };
        return jdbc.query("SELECT " + COLUMNS + " FROM inventory.location" + predicate
                + " ORDER BY code ASC,id ASC", this::map);
    }

    @Override
    @Transactional(readOnly = true)
    public LocationView get(UUID id) {
        return find(id, false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID id) {
        List<Boolean> rows = jdbc.query("SELECT active FROM inventory.location WHERE id=?",
                (rs, row) -> rs.getBoolean(1), id);
        return !rows.isEmpty() && rows.getFirst();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LocationView create(LocationUseCase.Create command) {
        LocationLocks.lockHierarchy(jdbc);
        validateParent(command.parentId());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO inventory.location(id,code,name,location_type,parent_id,active,version) "
                    + "VALUES (?,?,?,?,?,true,0)", id, command.code(), command.name(),
                    command.locationType().name(), command.parentId());
        } catch (DataIntegrityViolationException exception) {
            throw new LocationConflictException("DATA_CONFLICT", "Location code already exists");
        }
        LocationView created = find(id, false);
        appendEvent("CREATED", command.actorUserId(), command.correlationId(), null, created);
        return created;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LocationView update(LocationUseCase.Update command) {
        LocationLocks.lockHierarchy(jdbc);
        LocationView before = find(command.id(), true);
        requireVersion(before, command.expectedVersion());
        if (!before.active()) {
            throw new LocationConflictException("LOCATION_INACTIVE", "Inactive location cannot be updated");
        }
        boolean parentChanged = !java.util.Objects.equals(before.parentId(), command.parentId());
        if (parentChanged) validateProposedParent(command.id(), command.parentId());
        if (before.name().equals(command.name()) && before.locationType() == command.locationType()
                && !parentChanged) return before;

        int changed = jdbc.update("UPDATE inventory.location SET name=?,location_type=?,parent_id=?,"
                + "version=version+1,updated_at=now() WHERE id=? AND version=?", command.name(),
                command.locationType().name(), command.parentId(), command.id(), command.expectedVersion());
        if (changed != 1) throw versionConflict();
        LocationView after = find(command.id(), false);
        appendEvent("UPDATED", command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LocationView deactivate(LocationUseCase.Lifecycle command) {
        LocationLocks.lockHierarchy(jdbc);
        LocationView before = find(command.id(), true);
        requireVersion(before, command.expectedVersion());
        if (!before.active()) return before;
        if (existsInSubtree(command.id(), "l.id<>? AND l.active", command.id())) {
            throw new LocationConflictException("LOCATION_HAS_ACTIVE_DESCENDANTS",
                    "Location has active descendants");
        }
        if (existsInSubtree(command.id(), "EXISTS (SELECT 1 FROM inventory.stock_balance b "
                + "WHERE b.location_id=l.id AND b.quantity>0)")) {
            throw new LocationConflictException("LOCATION_HAS_STOCK", "Location subtree has positive stock");
        }
        if (existsInSubtree(command.id(), "EXISTS (SELECT 1 FROM inventory.physical_count c "
                + "WHERE c.location_id=l.id AND c.status IN ('DRAFT','IN_PROGRESS'))")) {
            throw new LocationConflictException("LOCATION_IN_USE", "Location subtree has an open physical count");
        }
        updateActive(command.id(), command.expectedVersion(), false);
        LocationView after = find(command.id(), false);
        appendEvent("DEACTIVATED", command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LocationView reactivate(LocationUseCase.Lifecycle command) {
        LocationLocks.lockHierarchy(jdbc);
        LocationView before = find(command.id(), true);
        requireVersion(before, command.expectedVersion());
        if (before.active()) return before;
        validateProposedParent(before.id(), before.parentId());
        updateActive(command.id(), command.expectedVersion(), true);
        LocationView after = find(command.id(), false);
        appendEvent("REACTIVATED", command.actorUserId(), command.correlationId(), before, after);
        return after;
    }

    private void updateActive(UUID id, long expectedVersion, boolean active) {
        int changed = jdbc.update("UPDATE inventory.location SET active=?,version=version+1,updated_at=now() "
                + "WHERE id=? AND version=?", active, id, expectedVersion);
        if (changed != 1) throw versionConflict();
    }

    private void validateProposedParent(UUID id, UUID parentId) {
        if (parentId == null) return;
        if (id.equals(parentId)) throw hierarchyConflict("Location cannot be its own parent");
        validateParent(parentId);
        Boolean descendant = jdbc.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM inventory.location WHERE id=?
                    UNION ALL
                    SELECT child.id FROM inventory.location child JOIN subtree s ON child.parent_id=s.id
                )
                SELECT EXISTS(SELECT 1 FROM subtree WHERE id=?)
                """, Boolean.class, id, parentId);
        if (Boolean.TRUE.equals(descendant)) throw hierarchyConflict("Parent belongs to the location subtree");
    }

    private void validateParent(UUID parentId) {
        if (parentId == null) return;
        List<Boolean> active = jdbc.query("SELECT active FROM inventory.location WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), parentId);
        if (active.isEmpty()) throw hierarchyConflict("Parent location does not exist");
        if (!active.getFirst()) throw hierarchyConflict("Parent location is inactive");
    }

    private boolean existsInSubtree(UUID root, String predicate, Object... extra) {
        Object[] args = new Object[extra.length + 1];
        args[0] = root;
        System.arraycopy(extra, 0, args, 1, extra.length);
        Boolean result = jdbc.queryForObject("WITH RECURSIVE subtree AS (SELECT id FROM inventory.location "
                + "WHERE id=? UNION ALL SELECT child.id FROM inventory.location child JOIN subtree s "
                + "ON child.parent_id=s.id) SELECT EXISTS(SELECT 1 FROM inventory.location l "
                + "JOIN subtree s ON s.id=l.id WHERE " + predicate + ")", Boolean.class, args);
        return Boolean.TRUE.equals(result);
    }

    private LocationView find(UUID id, boolean lock) {
        List<LocationView> rows = jdbc.query("SELECT " + COLUMNS + " FROM inventory.location WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), this::map, id);
        if (rows.isEmpty()) throw new LocationNotFoundException();
        return rows.getFirst();
    }

    private LocationView map(ResultSet rs, int row) throws SQLException {
        return new LocationView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                LocationType.valueOf(rs.getString("location_type")), rs.getObject("parent_id", UUID.class),
                rs.getBoolean("active"), rs.getLong("version"));
    }

    private void requireVersion(LocationView location, long expected) {
        if (location.version() != expected) throw versionConflict();
    }

    private LocationConflictException versionConflict() {
        return new LocationConflictException("VERSION_CONFLICT", "Location version does not match");
    }

    private LocationConflictException hierarchyConflict(String message) {
        return new LocationConflictException("LOCATION_HIERARCHY_CONFLICT", message);
    }

    private void appendEvent(String changeType, UUID actor, UUID correlationId,
            LocationView before, LocationView after) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "LocationChanged");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Location");
        envelope.put("aggregateId", after.id().toString());
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("correlationId", correlationId.toString());
        envelope.put("producer", "inventory-service");
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", changeType);
        payload.put("actorUserId", actor.toString());
        if (before == null) payload.putNull("before"); else payload.set("before", snapshot(before));
        payload.set("after", snapshot(after));
        try {
            jdbc.update("INSERT INTO inventory.outbox_event(id,aggregate_type,aggregate_id,event_type,"
                    + "schema_version,correlation_id,payload,status,occurred_at) "
                    + "VALUES (?,'Location',?,'LocationChanged',1,?,?::jsonb,'PENDING',?)", eventId,
                    after.id(), correlationId, mapper.writeValueAsString(envelope), Timestamp.from(occurredAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize LocationChanged", exception);
        }
    }

    private ObjectNode snapshot(LocationView location) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", location.id().toString());
        node.put("code", location.code());
        node.put("name", location.name());
        node.put("locationType", location.locationType().name());
        if (location.parentId() == null) node.putNull("parentId");
        else node.put("parentId", location.parentId().toString());
        node.put("active", location.active());
        node.put("version", location.version());
        return node;
    }
}
