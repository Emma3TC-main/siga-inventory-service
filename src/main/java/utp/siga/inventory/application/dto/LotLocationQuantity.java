package utp.siga.inventory.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LotLocationQuantity(UUID locationId, BigDecimal quantity) { }
