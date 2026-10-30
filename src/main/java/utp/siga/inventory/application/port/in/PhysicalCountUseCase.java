package utp.siga.inventory.application.port.in;

import java.math.BigDecimal;
import java.util.UUID;
import utp.siga.inventory.application.dto.PhysicalCountPage;
import utp.siga.inventory.domain.model.PhysicalCount;

public interface PhysicalCountUseCase {
    PhysicalCountPage list(int page, int size);
    PhysicalCount get(UUID id);
    PhysicalCount create(Create command);
    PhysicalCount capture(Capture command);
    PhysicalCount addUnexpected(AddUnexpected command);
    PhysicalCount close(Command command);
    PhysicalCount cancel(Cancel command);

    record Create(UUID locationId, String notes, UUID actorUserId, UUID idempotencyKey) { }
    record Capture(UUID countId, UUID lineId, long expectedVersion, BigDecimal countedQuantity,
            String notes, UUID actorUserId) { }
    record AddUnexpected(UUID countId, long expectedVersion, UUID productId, UUID lotId, UUID assetId,
            BigDecimal countedQuantity, String notes, UUID actorUserId, UUID idempotencyKey) { }
    record Command(UUID countId, long expectedVersion, UUID actorUserId, UUID idempotencyKey) { }
    record Cancel(UUID countId, long expectedVersion, String reason, UUID actorUserId,
            UUID idempotencyKey) { }
}
