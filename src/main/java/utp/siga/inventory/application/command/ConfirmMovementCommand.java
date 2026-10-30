package utp.siga.inventory.application.command;

import java.util.UUID;

public record ConfirmMovementCommand(UUID movementId, UUID idempotencyKey, UUID actorUserId, UUID correlationId,
        boolean actorCanAuthorize) {
    public ConfirmMovementCommand(UUID movementId, UUID idempotencyKey, UUID actorUserId, UUID correlationId) {
        this(movementId, idempotencyKey, actorUserId, correlationId, true);
    }
    public ConfirmMovementCommand {
        if (movementId == null || idempotencyKey == null || actorUserId == null || correlationId == null) {
            throw new IllegalArgumentException("movementId, idempotencyKey, actorUserId and correlationId are required");
        }
    }
}
