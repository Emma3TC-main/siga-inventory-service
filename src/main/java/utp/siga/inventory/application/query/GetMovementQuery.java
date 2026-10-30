package utp.siga.inventory.application.query;

import java.util.Optional;
import java.util.UUID;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.dto.MovementResponse;

public interface GetMovementQuery {
    Optional<MovementResponse> getMovement(UUID id);

    MovementPage getMovements(int page, int size);
}
