package utp.siga.inventory.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import utp.siga.inventory.domain.model.Movement;

public interface MovementRepository {
    String nextMovementCode(Instant createdAt);
    void save(Movement movement);
    Optional<Movement> findById(UUID id);
}
