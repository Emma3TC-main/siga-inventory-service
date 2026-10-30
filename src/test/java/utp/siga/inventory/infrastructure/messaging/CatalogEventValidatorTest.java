package utp.siga.inventory.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogEventValidatorTest {
    private final CatalogEventValidator validator = new CatalogEventValidator();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsUnknownRoutingKeyExplicitly() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("catalog.product.deleted.v1", json.readTree("{}")));
    }

    @Test
    void rejectsIncompleteProductUpdated() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ProductUpdated","schemaVersion":1,
                 "aggregateType":"Product","aggregateId":"%s","occurredAt":"2026-10-04T00:00:00Z",
                 "correlationId":"%s","producer":"catalog-service","payload":{"product":{
                 "id":"%s","sku":"SKU","productType":"MATERIAL","storageUnitCode":"KG",
                 "baseUnitCode":"KG","minStock":0,"requiresLot":false,"requiresHeatNumber":false,
                 "requiresExpiry":false,"requiresSerial":false,"active":true,"version":1},"conversions":[]}}
                """.formatted(UUID.randomUUID(), id, UUID.randomUUID(), id);

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("catalog.product.updated.v1", json.readTree(body)));
    }

    @Test
    void acceptsCanonicalProductUpdatedFields() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ProductUpdated","schemaVersion":1,
                 "aggregateType":"Product","aggregateId":"%s","occurredAt":"2026-10-04T00:00:00Z",
                 "correlationId":"%s","producer":"catalog-service","payload":{"product":{
                 "id":"%s","sku":"SKU","name":"Product","categoryId":"%s","categoryCode":"CAT",
                 "categoryName":"Category","productType":"MATERIAL","storageUnitCode":"KG",
                 "baseUnitCode":"KG","minStock":0.0000000,"requiresLot":false,"requiresHeatNumber":false,
                 "requiresExpiry":false,"requiresSerial":false,"active":true,"version":1},"conversions":[]}}
                """.formatted(UUID.randomUUID(), id, UUID.randomUUID(), id, UUID.randomUUID());

        assertEquals("ProductUpdated",
                validator.validate("catalog.product.updated.v1", json.readTree(body)));
    }
}
