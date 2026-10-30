package utp.siga.inventory.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MovementDetailResponse(
    UUID id,
    int lineNumber,
    UUID productId,
    BigDecimal enteredQuantity,
    String unitCode,
    BigDecimal conversionFactor,
    BigDecimal stockQuantity,
    UUID originLocationId,
    UUID destinationLocationId,
    UUID lotId,
    UUID assetId,
    String originalCurrency,
    BigDecimal originalUnitCost,
    BigDecimal exchangeRate,
    LocalDate exchangeRateDate,
    String exchangeRateSource,
    BigDecimal baseUnitCostPen,
    BigDecimal appliedAvgCostPen
) {}
