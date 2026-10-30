package utp.siga.inventory.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import utp.siga.inventory.domain.exception.InvalidMovementFieldException;

public class MovementDetail {
    private final UUID id;
    private final int lineNumber;
    private final UUID productId;
    private final BigDecimal enteredQuantity;
    private final String unitCode;
    private final BigDecimal conversionFactor;
    private final BigDecimal stockQuantity;
    private final UUID originLocationId;
    private final UUID destinationLocationId;
    private final UUID lotId;
    private final UUID assetId;
    private final String originalCurrency;
    private final BigDecimal originalUnitCost;
    private final BigDecimal exchangeRate;
    private final LocalDate exchangeRateDate;
    private final String exchangeRateSource;

    public MovementDetail(UUID id, int lineNumber, UUID productId, BigDecimal enteredQuantity, String unitCode,
                          BigDecimal conversionFactor, UUID originLocationId, UUID destinationLocationId, UUID lotId, UUID assetId,
                          String originalCurrency, BigDecimal originalUnitCost, BigDecimal exchangeRate,
                          LocalDate exchangeRateDate, String exchangeRateSource) {
        this.id = id != null ? id : UUID.randomUUID();
        this.lineNumber = lineNumber;
        this.productId = productId;
        this.enteredQuantity = enteredQuantity;
        this.unitCode = unitCode;
        this.conversionFactor = conversionFactor != null ? conversionFactor : BigDecimal.ONE;

        if (this.enteredQuantity == null || this.enteredQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidMovementFieldException("enteredQuantity must be > 0");
        }
        if (this.conversionFactor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidMovementFieldException("conversionFactor must be > 0");
        }

        this.stockQuantity = this.enteredQuantity.multiply(this.conversionFactor);

        this.originLocationId = originLocationId;
        this.destinationLocationId = destinationLocationId;
        this.lotId = lotId;
        this.assetId = assetId;

        this.originalCurrency = originalCurrency != null ? originalCurrency : "PEN";
        this.originalUnitCost = originalUnitCost;
        this.exchangeRate = exchangeRate;
        this.exchangeRateDate = exchangeRateDate;
        this.exchangeRateSource = exchangeRateSource;

        validateInvariants();
    }

    private void validateInvariants() {
        if (lineNumber <= 0) throw new InvalidMovementFieldException("lineNumber must be > 0");
        if (productId == null) throw new InvalidMovementFieldException("productId is required");
        if (unitCode == null || unitCode.isBlank()) throw new InvalidMovementFieldException("unitCode is required");
        if (originLocationId != null && destinationLocationId != null && originLocationId.equals(destinationLocationId)) {
            throw new InvalidMovementFieldException("origin and destination locations cannot be the same");
        }
        if (!"PEN".equals(originalCurrency)) {
            if (exchangeRate == null || exchangeRateDate == null || exchangeRateSource == null) {
                throw new InvalidMovementFieldException("Exchange rate details required for foreign currency");
            }
            if (exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidMovementFieldException("exchangeRate must be > 0");
            }
        }
    }

    public UUID getId() { return id; }
    public int getLineNumber() { return lineNumber; }
    public UUID getProductId() { return productId; }
    public BigDecimal getEnteredQuantity() { return enteredQuantity; }
    public String getUnitCode() { return unitCode; }
    public BigDecimal getConversionFactor() { return conversionFactor; }
    public BigDecimal getStockQuantity() { return stockQuantity; }
    public UUID getOriginLocationId() { return originLocationId; }
    public UUID getDestinationLocationId() { return destinationLocationId; }
    public UUID getLotId() { return lotId; }
    public UUID getAssetId() { return assetId; }
    public String getOriginalCurrency() { return originalCurrency; }
    public BigDecimal getOriginalUnitCost() { return originalUnitCost; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public LocalDate getExchangeRateDate() { return exchangeRateDate; }
    public String getExchangeRateSource() { return exchangeRateSource; }
}
