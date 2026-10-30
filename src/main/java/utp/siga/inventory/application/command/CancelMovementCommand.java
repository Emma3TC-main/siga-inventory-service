package utp.siga.inventory.application.command;

import java.util.UUID;

public record CancelMovementCommand(
    UUID movementId
) {}
