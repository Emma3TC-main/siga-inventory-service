package utp.siga.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CatalogEventValidator {
    private static final Map<String, String> EVENT_BY_ROUTE = Map.of(
            "catalog.product.created.v1", "ProductCreated",
            "catalog.product.updated.v1", "ProductUpdated",
            "catalog.supplier.created.v1", "SupplierCreated",
            "catalog.supplier.updated.v1", "SupplierUpdated",
            "catalog.supplier.disabled.v1", "SupplierDisabled");

    public String validate(String routingKey, JsonNode envelope) {
        String expectedEvent = EVENT_BY_ROUTE.get(routingKey);
        if (expectedEvent == null) throw new IllegalArgumentException("Unsupported Catalog routing key");
        if (!envelope.isObject()
                || !expectedEvent.equals(text(envelope.path("eventType"), 120))
                || !envelope.path("schemaVersion").isIntegralNumber()
                || envelope.path("schemaVersion").intValue() != 1
                || !"catalog-service".equals(text(envelope.path("producer"), 120))) {
            throw new IllegalArgumentException("Invalid Catalog event envelope");
        }
        uuid(envelope.path("eventId"));
        UUID aggregateId = uuid(envelope.path("aggregateId"));
        uuid(envelope.path("correlationId"));
        if (envelope.has("causationId")) uuid(envelope.path("causationId"));
        Instant.parse(requiredText(envelope.path("occurredAt")));
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) throw new IllegalArgumentException("Catalog payload must be an object");

        if (expectedEvent.startsWith("Product")) {
            if (!"Product".equals(text(envelope.path("aggregateType"), 80)))
                throw new IllegalArgumentException("Invalid Product aggregate type");
            validateProduct(payload, expectedEvent.equals("ProductUpdated"), aggregateId);
        } else {
            if (!"Supplier".equals(text(envelope.path("aggregateType"), 80)))
                throw new IllegalArgumentException("Invalid Supplier aggregate type");
            validateSupplier(payload.path("supplier"), expectedEvent, aggregateId);
        }
        return expectedEvent;
    }

    private void validateProduct(JsonNode payload, boolean updated, UUID aggregateId) {
        JsonNode product = payload.path("product");
        if (!product.isObject() || !payload.path("conversions").isArray())
            throw new IllegalArgumentException("Invalid Product payload");
        if (!aggregateId.equals(uuid(product.path("id")))
                || text(product.path("sku"), 80) == null
                || !Set.of("MATERIAL", "INSUMO", "REPUESTO", "MAQUINARIA").contains(requiredText(product.path("productType")))
                || text(product.path("storageUnitCode"), 20) == null
                || text(product.path("baseUnitCode"), 20) == null
                || !decimal(product.path("minStock"), false)
                || !booleanValue(product.path("requiresLot"))
                || !booleanValue(product.path("requiresHeatNumber"))
                || !booleanValue(product.path("requiresExpiry"))
                || !booleanValue(product.path("requiresSerial"))
                || !booleanValue(product.path("active"))
                || !nonNegativeLong(product.path("version"))) {
            throw new IllegalArgumentException("Invalid Product fields");
        }
        if (updated) {
            uuid(product.path("categoryId"));
            if (text(product.path("name"), 200) == null
                || text(product.path("categoryCode"), 50) == null
                || text(product.path("categoryName"), 120) == null) {
                throw new IllegalArgumentException("Incomplete ProductUpdated payload");
            }
        }
        for (JsonNode conversion : payload.path("conversions")) {
            if (!conversion.isObject()
                    || text(conversion.path("fromUnitCode"), 20) == null
                    || text(conversion.path("toUnitCode"), 20) == null
                    || !decimal(conversion.path("factor"), true)
                    || !booleanValue(conversion.path("active"))) {
                throw new IllegalArgumentException("Invalid Product conversion");
            }
        }
    }

    private void validateSupplier(JsonNode supplier, String eventType, UUID aggregateId) {
        if (!supplier.isObject()
                || !aggregateId.equals(uuid(supplier.path("id")))
                || text(supplier.path("code"), 50) == null
                || text(supplier.path("taxId"), 20) == null
                || text(supplier.path("businessName"), 200) == null
                || !booleanValue(supplier.path("active"))
                || !nonNegativeLong(supplier.path("version"))
                || (eventType.equals("SupplierDisabled") && supplier.path("active").asBoolean())) {
            throw new IllegalArgumentException("Invalid Supplier payload");
        }
    }

    private UUID uuid(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("UUID field is required");
        return UUID.fromString(value.textValue());
    }

    private String requiredText(JsonNode value) {
        String result = text(value, Integer.MAX_VALUE);
        if (result == null) throw new IllegalArgumentException("Text field is required");
        return result;
    }

    private String text(JsonNode value, int maximum) {
        return value.isTextual() && value.textValue().length() <= maximum ? value.textValue() : null;
    }

    private boolean booleanValue(JsonNode value) { return value.isBoolean(); }

    private boolean nonNegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private boolean decimal(JsonNode value, boolean positive) {
        if (!value.isNumber()) return false;
        BigDecimal number = value.decimalValue();
        try {
            BigDecimal normalized = number.setScale(6, RoundingMode.UNNECESSARY);
            return normalized.precision() <= 18
                    && (positive ? number.signum() > 0 : number.signum() >= 0);
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
