package utp.siga.inventory.application.port.out;

import java.util.UUID;

public interface EvidenceAvailabilityPort {
    boolean hasAvailableMovementEvidence(UUID movementId, String bearerToken, UUID correlationId);
}
