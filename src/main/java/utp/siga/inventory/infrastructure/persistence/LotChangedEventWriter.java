package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import utp.siga.inventory.application.dto.LotAdminView;
import utp.siga.inventory.application.dto.LotStatus;

@Component
public class LotChangedEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public LotChangedEventWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void append(String changeType, UUID actor, UUID correlationId,
            LotAdminView before, LotAdminView after) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "LotChanged");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Lot");
        envelope.put("aggregateId", after.id().toString());
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("correlationId", correlationId.toString());
        envelope.put("producer", "inventory-service");
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", changeType);
        if (actor == null) payload.putNull("actorUserId"); else payload.put("actorUserId", actor.toString());
        if (before == null) payload.putNull("before"); else payload.set("before", snapshot(before));
        payload.set("after", snapshot(after));
        try {
            jdbc.update("INSERT INTO inventory.outbox_event(id,aggregate_type,aggregate_id,event_type,"
                    + "schema_version,correlation_id,payload,status,occurred_at) "
                    + "VALUES (?,'Lot',?,'LotChanged',1,?,?::jsonb,'PENDING',?)", eventId,
                    after.id(), correlationId, mapper.writeValueAsString(envelope), Timestamp.from(occurredAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize LotChanged", exception);
        }
    }

    public LotAdminView snapshot(UUID id) {
        return jdbc.queryForObject("""
                SELECT id,product_id,lot_number,heat_number,manufactured_at,received_at,expires_at,status,
                       version,created_at,updated_at
                FROM inventory.lot WHERE id=?
                """, (rs, row) -> new LotAdminView(
                        rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("lot_number"), rs.getString("heat_number"),
                        rs.getObject("manufactured_at", LocalDate.class), rs.getObject("received_at", LocalDate.class),
                        rs.getObject("expires_at", LocalDate.class), LotStatus.valueOf(rs.getString("status")),
                        false, java.math.BigDecimal.ZERO, java.util.List.of(), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id);
    }

    private ObjectNode snapshot(LotAdminView lot) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", lot.id().toString());
        node.put("productId", lot.productId().toString());
        nullable(node, "lotNumber", lot.lotNumber());
        nullable(node, "heatNumber", lot.heatNumber());
        nullable(node, "manufacturedAt", lot.manufacturedAt());
        nullable(node, "receivedAt", lot.receivedAt());
        nullable(node, "expiresAt", lot.expiresAt());
        node.put("status", lot.status().name());
        node.put("version", lot.version());
        node.put("createdAt", lot.createdAt().toString());
        node.put("updatedAt", lot.updatedAt().toString());
        return node;
    }

    private static void nullable(ObjectNode node, String name, Object value) {
        if (value == null) node.putNull(name); else node.put(name, value.toString());
    }
}
