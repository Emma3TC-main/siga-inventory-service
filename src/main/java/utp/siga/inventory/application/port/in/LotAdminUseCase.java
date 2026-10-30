package utp.siga.inventory.application.port.in;

import java.time.LocalDate;
import java.util.UUID;
import utp.siga.inventory.application.dto.LotAdminPage;
import utp.siga.inventory.application.dto.LotAdminView;
import utp.siga.inventory.application.dto.LotStatus;

public interface LotAdminUseCase {
    LotAdminPage list(Filter filter);
    LotAdminView get(UUID id);
    LotAdminView create(Create command);
    LotAdminView update(Update command);
    LotAdminView changeStatus(ChangeStatus command);

    record Filter(UUID productId, LotStatus status, Boolean expired, UUID locationId, int page, int size) { }
    record Create(UUID productId, String lotNumber, String heatNumber, LocalDate manufacturedAt,
            LocalDate receivedAt, LocalDate expiresAt, UUID actorUserId, UUID correlationId) { }
    record Update(UUID id, LocalDate manufacturedAt, LocalDate receivedAt, LocalDate expiresAt,
            long expectedVersion, UUID actorUserId, UUID correlationId) { }
    record ChangeStatus(UUID id, LotStatus targetStatus, long expectedVersion,
            UUID actorUserId, UUID correlationId) { }
}
