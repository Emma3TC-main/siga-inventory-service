package utp.siga.inventory.application.dto;

import java.time.Instant;
import java.util.UUID;

public record MovementHistoryItem(
        UUID id,
        String movementCode,
        String movementType,
        String status,
        boolean sensitive,
        UUID registeredByUserId,
        UUID requestedByUserId,
        String costCenterCode,
        String costCenterNameSnapshot,
        String referenceDocType,
        String referenceDocNumber,
        String reason,
        Instant createdAt,
        Instant submittedAt,
        Instant confirmedAt) {
}
