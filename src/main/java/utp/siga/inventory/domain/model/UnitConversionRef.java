package utp.siga.inventory.domain.model;

import java.math.BigDecimal;

public record UnitConversionRef(
    String fromUnitCode,
    String toUnitCode,
    BigDecimal factor,
    boolean active
) {}
