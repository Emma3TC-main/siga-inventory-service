package utp.siga.inventory.application.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import utp.siga.inventory.domain.model.MovementType;

public record CreateMovementCommand(
    MovementType movementType,
    UUID requestedByUserId,
    String costCenterCode,
    String costCenterNameSnapshot,
    UUID supplierId,
    String referenceDocType,
    String referenceDocNumber,
    String reason,
    String notes,
    List<Detail> details
) {
    public record Detail(
        UUID productId,
        BigDecimal enteredQuantity,
        String unitCode,
        UUID originLocationId,
        UUID destinationLocationId,
        UUID lotId,
        UUID assetId,
        String originalCurrency,
        BigDecimal originalUnitCost,
        BigDecimal exchangeRate,
        LocalDate exchangeRateDate,
        String exchangeRateSource
    ) {}
}
