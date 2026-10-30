package utp.siga.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductRef(
    UUID id,
    String sku,
    String productType,
    String storageUnitCode,
    String baseUnitCode,
    BigDecimal minStock,
    boolean requiresLot,
    boolean requiresHeatNumber,
    boolean requiresSerial,
    boolean requiresExpiry,
    boolean active,
    long sourceVersion,
    Instant updatedAt,
    List<UnitConversionRef> conversions
) {}
