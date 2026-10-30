package utp.siga.inventory.application.port.in;

import java.util.UUID;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.command.AuthorizeMovementCommand;
import utp.siga.inventory.application.command.RejectMovementCommand;
import utp.siga.inventory.application.command.CancelMovementCommand;
import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.domain.model.Movement;

public interface MovementUseCase {
    Movement createMovement(CreateMovementCommand command, UUID actorUserId);
    Movement submitMovement(SubmitMovementCommand command, boolean actorCanAuthorize);
    Movement authorizeMovement(AuthorizeMovementCommand command, UUID actorUserId, boolean stepUpVerified);
    Movement rejectMovement(RejectMovementCommand command, UUID actorUserId, boolean stepUpVerified);
    Movement cancelMovement(CancelMovementCommand command);
    Movement confirmMovement(ConfirmMovementCommand command);
}
