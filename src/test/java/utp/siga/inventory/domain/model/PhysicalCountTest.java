package utp.siga.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import utp.siga.inventory.application.exception.PhysicalCountConflictException;
import utp.siga.inventory.application.exception.PhysicalCountValidationException;

class PhysicalCountTest {
    @Test
    void firstCaptureStartsDraftAndTerminalStatesRejectMutation() {
        assertEquals(PhysicalCountStatus.IN_PROGRESS, count(PhysicalCountStatus.DRAFT, List.of()).statusAfterCapture());
        assertThrows(PhysicalCountConflictException.class,
                () -> count(PhysicalCountStatus.COMPLETED, List.of()).statusAfterCapture());
        assertThrows(PhysicalCountConflictException.class,
                () -> count(PhysicalCountStatus.CANCELLED, List.of()).requireOpen());
    }

    @Test
    void closeAllowsVerifiedEmptyDraftButRejectsIncompleteOrUnstartedNonEmptyCount() {
        count(PhysicalCountStatus.DRAFT, List.of()).validateClose();
        PhysicalCountLine uncaptured = line(null, null);
        assertThrows(PhysicalCountValidationException.class,
                () -> count(PhysicalCountStatus.DRAFT, List.of(uncaptured)).validateClose());
        assertThrows(PhysicalCountValidationException.class,
                () -> count(PhysicalCountStatus.IN_PROGRESS, List.of(uncaptured)).validateClose());
        count(PhysicalCountStatus.IN_PROGRESS, List.of(line(BigDecimal.ONE, UUID.randomUUID()))).validateClose();
    }

    @Test
    void differenceUsesBigDecimalAndSerializedAssetQuantitiesAreBinary() {
        PhysicalCountLine line = new PhysicalCountLine(UUID.randomUUID(), UUID.randomUUID(), null, null,
                new BigDecimal("1.250000"), new BigDecimal("1.750000"), null, UUID.randomUUID(), Instant.now());
        assertEquals(0, new BigDecimal("0.500000").compareTo(line.differenceQuantity()));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCountLine(UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID(), BigDecimal.ONE, new BigDecimal("2"),
                null, UUID.randomUUID(), Instant.now()));
    }

    @Test
    void discrepancyExposesManualAdjustmentCandidateBySignOnly() {
        UUID actor = UUID.randomUUID();
        Instant now = Instant.now();
        PhysicalCountLine positive = new PhysicalCountLine(UUID.randomUUID(), UUID.randomUUID(), null, null,
                BigDecimal.ONE, new BigDecimal("2"), null, actor, now);
        PhysicalCountLine negative = new PhysicalCountLine(UUID.randomUUID(), UUID.randomUUID(), null, null,
                new BigDecimal("2"), BigDecimal.ONE, null, actor, now);
        PhysicalCountLine zero = new PhysicalCountLine(UUID.randomUUID(), UUID.randomUUID(), null, null,
                BigDecimal.ONE, BigDecimal.ONE, null, actor, now);
        assertEquals(MovementType.ADJUST_POSITIVE, positive.adjustmentCandidate());
        assertEquals(MovementType.ADJUST_NEGATIVE, negative.adjustmentCandidate());
        assertEquals(null, zero.adjustmentCandidate());
    }

    private static PhysicalCount count(PhysicalCountStatus status, List<PhysicalCountLine> lines) {
        Instant now = Instant.now();
        return new PhysicalCount(UUID.randomUUID(), UUID.randomUUID(), status, 0, null,
                UUID.randomUUID(), null, null, null, null, null, null, now, now, lines);
    }

    private static PhysicalCountLine line(BigDecimal counted, UUID actor) {
        return new PhysicalCountLine(UUID.randomUUID(), UUID.randomUUID(), null, null,
                BigDecimal.ONE, counted, null, actor, counted == null ? null : Instant.now());
    }
}
