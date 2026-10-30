package utp.siga.inventory.application.command;

import java.util.UUID;

public record SubmitMovementCommand(
    UUID movementId,
    String bearerToken,
    UUID correlationId
) {
    public SubmitMovementCommand(UUID movementId) {
        this(movementId, null, null);
    }
}
