package utp.siga.inventory.application.query;

import java.time.Instant;
import java.util.UUID;
import utp.siga.inventory.domain.model.MovementStatus;
import utp.siga.inventory.domain.model.MovementType;

public record MovementHistoryFilter(
        int page,
        int size,
        String movementCode,
        MovementType movementType,
        MovementStatus status,
        UUID productId,
        UUID lotId,
        UUID assetId,
        UUID sourceLocationId,
        UUID destinationLocationId,
        UUID locationId,
        UUID registeredByUserId,
        UUID requestedByUserId,
        Instant from,
        Instant to,
        Boolean sensitive,
        String referenceDocNumber) {

    public MovementHistoryFilter {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        if (movementCode != null && movementCode.length() > 80) {
            throw new IllegalArgumentException("movementCode must not exceed 80 characters");
        }
        if (referenceDocNumber != null && referenceDocNumber.length() > 120) {
            throw new IllegalArgumentException("referenceDocNumber must not exceed 120 characters");
        }
    }

    public boolean hasDetailFilters() {
        return productId != null || lotId != null || assetId != null || sourceLocationId != null
                || destinationLocationId != null || locationId != null;
    }
}
