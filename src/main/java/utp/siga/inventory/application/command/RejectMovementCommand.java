package utp.siga.inventory.application.command;

public record RejectMovementCommand(
    java.util.UUID movementId,
    String reason
) {}
