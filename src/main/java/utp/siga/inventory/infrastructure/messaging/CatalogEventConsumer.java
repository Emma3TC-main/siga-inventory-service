package utp.siga.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import utp.siga.inventory.application.usecase.SyncCatalogProjectionUseCase;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.SupplierRef;
import utp.siga.inventory.domain.model.UnitConversionRef;

@Component
public class CatalogEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(CatalogEventConsumer.class);
    private final SyncCatalogProjectionUseCase sync;
    private final ObjectMapper mapper;
    private final CatalogEventValidator validator;

    public CatalogEventConsumer(SyncCatalogProjectionUseCase sync, ObjectMapper mapper, CatalogEventValidator validator) {
        this.sync = sync;
        this.mapper = mapper;
        this.validator = validator;
    }

    @RabbitListener(queues = "${inventory.catalog.consumer.queue}",
            containerFactory = "catalogEventListenerContainerFactory",
            autoStartup = "${inventory.catalog.consumer.enabled}")
    public void onEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        try {
            JsonNode envelope = mapper.readTree(message.getBody());
            String eventType = validator.validate(routingKey, envelope);
            UUID eventId = UUID.fromString(envelope.get("eventId").asText());
            Instant occurredAt = Instant.parse(envelope.get("occurredAt").asText());
            JsonNode payload = envelope.get("payload");

            if (eventType.startsWith("Supplier")) {
                sync.syncSupplier(eventId, parseSupplier(payload.get("supplier"), occurredAt));
            } else {
                sync.syncProduct(eventId, parseProduct(payload, occurredAt));
            }
        } catch (IllegalArgumentException e) {
            log.warn("Rejected invalid Catalog event on routing key {}", routingKey);
            throw new AmqpRejectAndDontRequeueException("Invalid Catalog event", e);
        } catch (Exception e) {
            log.error("Catalog projection failed for routing key {}", routingKey, e);
            throw new RuntimeException("Catalog projection failed", e);
        }
    }

    private SupplierRef parseSupplier(JsonNode s, Instant occurredAt) {
        return new SupplierRef(
            UUID.fromString(s.get("id").asText()),
            s.get("code").asText(),
            s.get("taxId").asText(),
            s.get("businessName").asText(),
            s.get("active").asBoolean(),
            s.get("version").asLong(),
            occurredAt
        );
    }

    private ProductRef parseProduct(JsonNode payload, Instant occurredAt) {
        JsonNode p = payload.get("product");
        List<UnitConversionRef> conversions = new ArrayList<>();
        if (payload.has("conversions")) {
            for (JsonNode c : payload.get("conversions")) {
                conversions.add(new UnitConversionRef(
                    c.get("fromUnitCode").asText(),
                    c.get("toUnitCode").asText(),
                    new BigDecimal(c.get("factor").asText()),
                    c.get("active").asBoolean()
                ));
            }
        }
        return new ProductRef(
            UUID.fromString(p.get("id").asText()),
            p.get("sku").asText(),
            p.get("productType").asText(),
            p.get("storageUnitCode").asText(),
            p.get("baseUnitCode").asText(),
            new BigDecimal(p.get("minStock").asText()),
            p.has("requiresLot") && p.get("requiresLot").asBoolean(),
            p.has("requiresHeatNumber") && p.get("requiresHeatNumber").asBoolean(),
            p.has("requiresSerial") && p.get("requiresSerial").asBoolean(),
            p.has("requiresExpiry") && p.get("requiresExpiry").asBoolean(),
            p.get("active").asBoolean(),
            p.get("version").asLong(),
            occurredAt,
            conversions
        );
    }
}
