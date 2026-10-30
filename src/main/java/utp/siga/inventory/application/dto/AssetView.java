package utp.siga.inventory.application.dto;

import java.util.UUID;

public record AssetView(
        UUID id,
        UUID productId,
        String assetCode,
        String serialNumber,
        AssetOperationalStatus operationalStatus,
        String brand,
        String model,
        UUID locationId,
        UUID lotId,
        boolean available) {
}
