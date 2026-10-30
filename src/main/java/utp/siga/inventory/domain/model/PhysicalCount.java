package utp.siga.inventory.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import utp.siga.inventory.application.exception.PhysicalCountConflictException;
import utp.siga.inventory.application.exception.PhysicalCountValidationException;

public record PhysicalCount(
        UUID id,
        UUID locationId,
        PhysicalCountStatus status,
        long version,
        String notes,
        UUID createdByUserId,
        Instant startedAt,
        UUID completedByUserId,
        Instant completedAt,
        UUID cancelledByUserId,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        List<PhysicalCountLine> lines) {

    public PhysicalCount {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new PhysicalCountConflictException("VERSION_CONFLICT", "Physical count version is stale");
        }
    }

    public void requireOpen() {
        if (status == PhysicalCountStatus.COMPLETED || status == PhysicalCountStatus.CANCELLED) {
            throw new PhysicalCountConflictException("PHYSICAL_COUNT_STATE_CONFLICT",
                    "Physical count is already terminal");
        }
    }

    public PhysicalCountStatus statusAfterCapture() {
        requireOpen();
        return status == PhysicalCountStatus.DRAFT ? PhysicalCountStatus.IN_PROGRESS : status;
    }

    public void validateClose() {
        requireOpen();
        if (lines.stream().anyMatch(line -> line.countedQuantity() == null)) {
            throw new PhysicalCountValidationException("INCOMPLETE_PHYSICAL_COUNT",
                    "Every physical count line must be captured before closing");
        }
    }

    public void validateCancellationReason(String reason) {
        requireOpen();
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new PhysicalCountValidationException("VALIDATION_ERROR",
                    "Cancellation reason is required and must not exceed 255 characters");
        }
    }
}
