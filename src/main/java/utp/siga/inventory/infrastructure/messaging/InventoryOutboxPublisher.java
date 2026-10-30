package utp.siga.inventory.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(InventoryOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String exchange;

    public InventoryOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit,
            @Value("${inventory.outbox.exchange:siga.events}") String exchange) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.exchange = exchange;
    }

    @Transactional
    public int publishPending() {
        List<Outbox> events = jdbc.query("""
                SELECT id, event_type, payload::text FROM inventory.outbox_event
                WHERE status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                ORDER BY occurred_at, id FOR UPDATE SKIP LOCKED
                LIMIT 50
                """, (rs, row) -> new Outbox((UUID) rs.getObject(1), rs.getString(2), rs.getString(3)));
        int published = 0;
        for (Outbox event : events) {
            String routingKey = routingKey(event.type());
            if (routingKey == null) {
                fail(event.id());
                continue;
            }
            try {
                MessageProperties properties = new MessageProperties();
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                CorrelationData correlation = new CorrelationData(event.id().toString());
                rabbit.send(exchange, routingKey,
                        new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ publisher confirm rejected event " + event.id());
                jdbc.update("UPDATE inventory.outbox_event SET status='PUBLISHED', published_at=now() WHERE id=? AND status='PENDING'", event.id());
                published++;
            } catch (Exception exception) {
                fail(event.id());
                log.warn("Inventory outbox publication failed; eventId={} remains retryable", event.id(), exception);
            }
        }
        return published;
    }

    @Scheduled(initialDelayString = "${inventory.outbox.publisher.initial-delay-ms}", fixedDelayString = "${inventory.outbox.publisher.fixed-delay-ms}")
    public void publishScheduled() {
        publishPending();
    }

    private void fail(UUID id) {
        jdbc.update("""
                UPDATE inventory.outbox_event SET attempts=attempts+1, next_attempt_at=now() + interval '1 second', status='PENDING'
                WHERE id=?
                """, id);
    }

    private String routingKey(String eventType) {
        return switch (eventType) {
            case "MovementConfirmed" -> "inventory.movement.confirmed.v1";
            case "LocationChanged" -> "inventory.location.changed.v1";
            case "LotChanged" -> "inventory.lot.changed.v1";
            case "AssetChanged" -> "inventory.asset.changed.v1";
            default -> null;
        };
    }

    private record Outbox(UUID id, String type, String payload) { }
}
