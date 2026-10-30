package utp.siga.inventory.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record StockItem(
    UUID id,
    UUID productId,
    UUID locationId,
    UUID lotId,
    UUID assetId,
    BigDecimal quantity,
    BigDecimal avgCostPen
) {}
