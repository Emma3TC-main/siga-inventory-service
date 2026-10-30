package utp.siga.inventory.application.port.out;

import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.domain.model.Movement;

public interface MovementConfirmationPort {
    Movement confirm(ConfirmMovementCommand command);
}
