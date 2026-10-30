package utp.siga.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import utp.siga.inventory.domain.exception.InvalidMovementStateException;

class MovementTest {

    @Test
    void createValidDraftMovement() {
        assertEquals(MovementStatus.DRAFT, entryDraft().build().getStatus());
    }

    @Test
    void submitSensitiveMovementTransitionsToPendingAuthorization() {
        Movement movement = entryDraft().isSensitive(true).sensitivityReason("High value").build();
        movement.submit();
        assertEquals(MovementStatus.PENDING_AUTHORIZATION, movement.getStatus());
    }

    @Test
    void submitNonSensitiveMovementCanEnterApprovalFlow() {
        Movement movement = entryDraft().build();
        movement.submit();
        assertEquals(MovementStatus.PENDING_AUTHORIZATION, movement.getStatus());
    }

    @Test
    void authorizeRequiresRecentStepUp() {
        Movement movement = entryDraft().isSensitive(true).sensitivityReason("High value").build();
        movement.submit();
        assertThrows(IllegalArgumentException.class, () -> movement.authorize(UUID.randomUUID(), false, "Approved"));
        movement.authorize(UUID.randomUUID(), true, "Approved");
        assertEquals(MovementStatus.AUTHORIZED, movement.getStatus());
    }

    @Test
    void confirmedMovementIsImmutableToFurtherTransitions() {
        Movement movement = entryDraft().build();
        movement.confirm();
        assertEquals(MovementStatus.CONFIRMED, movement.getStatus());
        assertThrows(InvalidMovementStateException.class, movement::cancel);
        assertThrows(InvalidMovementStateException.class, movement::submit);
    }

    @Test
    void cancelSupportsOnlyCanonicalPreConfirmationStates() {
        Movement draft = entryDraft().build();
        draft.cancel();
        assertEquals(MovementStatus.CANCELLED, draft.getStatus());

        Movement pending = entryDraft().isSensitive(true).sensitivityReason("review").build();
        pending.submit();
        pending.cancel();
        assertEquals(MovementStatus.CANCELLED, pending.getStatus());

        Movement authorized = entryDraft().isSensitive(true).sensitivityReason("review").build();
        authorized.submit();
        authorized.authorize(UUID.randomUUID(), true, "Approved");
        authorized.cancel();
        assertEquals(MovementStatus.CANCELLED, authorized.getStatus());
    }

    @Test
    void invariantRequiresAtLeastOneDetail() {
        assertThrows(IllegalArgumentException.class, () -> new Movement.Builder()
                .movementCode("MOV-2026-00001").type(MovementType.ENTRY).registeredByUserId(UUID.randomUUID()).build());
    }

    @Test
    void invariantRequiresCanonicalLocationsByMovementType() {
        assertThrows(IllegalArgumentException.class, () -> movement(MovementType.ENTRY, null, null).build());
        assertThrows(IllegalArgumentException.class, () -> movement(MovementType.EXIT, null, UUID.randomUUID()).build());
        assertThrows(IllegalArgumentException.class, () -> movement(MovementType.TRANSFER, UUID.randomUUID(), null).build());
    }

    @Test
    void invariantRequiresReasonForAdjustments() {
        MovementDetail detail = detail(null, null);
        assertThrows(IllegalArgumentException.class, () -> new Movement.Builder()
                .movementCode("MOV-2026-00001").type(MovementType.ADJUST_POSITIVE).registeredByUserId(UUID.randomUUID()).addDetail(detail).build());
    }

    @Test
    void invariantSensitiveRequiresReasonAndSupplierIsOnlyForEntry() {
        assertThrows(IllegalArgumentException.class, () -> entryDraft().isSensitive(true).build());
        assertThrows(IllegalArgumentException.class, () -> movement(MovementType.EXIT, UUID.randomUUID(), null)
                .supplierId(UUID.randomUUID()).supplierNameSnapshot("Name").supplierTaxIdSnapshot("TaxId").build());
    }

    private static Movement.Builder entryDraft() {
        return movement(MovementType.ENTRY, null, UUID.randomUUID());
    }

    private static Movement.Builder movement(MovementType type, UUID origin, UUID destination) {
        return new Movement.Builder().movementCode("MOV-2026-00001").type(type).registeredByUserId(UUID.randomUUID())
                .addDetail(detail(origin, destination));
    }

    private static MovementDetail detail(UUID origin, UUID destination) {
        return new MovementDetail(null, 1, UUID.randomUUID(), BigDecimal.ONE, "EA", BigDecimal.ONE,
                origin, destination, null, null, "PEN", null, null, null, null);
    }
}
