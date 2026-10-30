package utp.siga.inventory.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record LotView(
        UUID id,
        UUID productId,
        String lotNumber,
        String heatNumber,
        LocalDate manufacturedAt,
        LocalDate receivedAt,
        LocalDate expiresAt,
        LotStatus status,
        UUID locationId,
        BigDecimal availableQuantity) {
}
