package utp.siga.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PhysicalCountLine(
        UUID id,
        UUID productId,
        UUID lotId,
        UUID assetId,
        BigDecimal systemQuantity,
        BigDecimal countedQuantity,
        String notes,
        UUID countedByUserId,
        Instant countedAt) {

    public PhysicalCountLine {
        if (id == null || productId == null || systemQuantity == null || systemQuantity.signum() < 0) {
            throw new IllegalArgumentException("Physical count line identity and non-negative system quantity are required");
        }
        if (countedQuantity != null && countedQuantity.signum() < 0) {
            throw new IllegalArgumentException("countedQuantity must be non-negative");
        }
        if ((countedQuantity == null && (countedByUserId != null || countedAt != null))
                || (countedQuantity != null && (countedByUserId == null || countedAt == null))) {
            throw new IllegalArgumentException("A captured quantity requires actor and timestamp");
        }
        if (assetId != null && (!binary(systemQuantity) || (countedQuantity != null && !binary(countedQuantity)))) {
            throw new IllegalArgumentException("Serialized asset quantities must be zero or one");
        }
    }

    public BigDecimal differenceQuantity() {
        return countedQuantity == null ? null : countedQuantity.subtract(systemQuantity);
    }

    public MovementType adjustmentCandidate() {
        BigDecimal difference = differenceQuantity();
        if (difference == null || difference.signum() == 0) return null;
        return difference.signum() > 0 ? MovementType.ADJUST_POSITIVE : MovementType.ADJUST_NEGATIVE;
    }

    private static boolean binary(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) == 0 || value.compareTo(BigDecimal.ONE) == 0;
    }
}
