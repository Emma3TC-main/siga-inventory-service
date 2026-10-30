package utp.siga.inventory.application.command;

public record AuthorizeMovementCommand(
    java.util.UUID movementId,
    String reason
) {}
