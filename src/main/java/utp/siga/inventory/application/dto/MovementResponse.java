package utp.siga.inventory.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MovementResponse(
    UUID id,
    String movementCode,
    String movementType,
    String status,
    UUID registeredByUserId,
    UUID requestedByUserId,
    String costCenterCode,
    String costCenterNameSnapshot,
    UUID supplierId,
    String supplierNameSnapshot,
    String supplierTaxIdSnapshot,
    boolean sensitive,
    String sensitivityReason,
    List<MovementDetailResponse> details,
    Instant createdAt,
    Instant submittedAt,
    Instant confirmedAt
) {}
