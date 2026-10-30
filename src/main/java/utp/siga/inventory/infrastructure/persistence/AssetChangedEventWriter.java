package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import utp.siga.inventory.application.dto.AssetAdminView;

@Component
public class AssetChangedEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AssetChangedEventWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void append(String changeType, UUID actor, UUID correlationId,
            AssetAdminView before, AssetAdminView after) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "AssetChanged");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Asset");
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
                    + "VALUES (?,'Asset',?,'AssetChanged',1,?,?::jsonb,'PENDING',?)", eventId,
                    after.id(), correlationId, mapper.writeValueAsString(envelope), Timestamp.from(occurredAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize AssetChanged", exception);
        }
    }

    private ObjectNode snapshot(AssetAdminView asset) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", asset.id().toString());
        node.put("productId", asset.productId().toString());
        node.put("assetCode", asset.assetCode());
        node.put("serialNumber", asset.serialNumber());
        nullable(node, "brand", asset.brand());
        nullable(node, "model", asset.model());
        node.put("operationalStatus", asset.operationalStatus().name());
        node.set("technicalSnapshot", asset.technicalSnapshot().deepCopy());
        node.put("version", asset.version());
        node.put("createdAt", asset.createdAt().toString());
        node.put("updatedAt", asset.updatedAt().toString());
        return node;
    }

    private static void nullable(ObjectNode node, String name, String value) {
        if (value == null) node.putNull(name); else node.put(name, value);
    }
}
