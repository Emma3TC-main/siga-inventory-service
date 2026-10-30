package utp.siga.inventory.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AssetAdminView(
        UUID id,
        UUID productId,
        String assetCode,
        String serialNumber,
        String brand,
        String model,
        AssetOperationalStatus operationalStatus,
        JsonNode technicalSnapshot,
        UUID currentLocationId,
        UUID currentLotId,
        boolean available,
        long version,
        Instant createdAt,
        Instant updatedAt) { }
