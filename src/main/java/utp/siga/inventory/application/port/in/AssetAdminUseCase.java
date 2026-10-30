package utp.siga.inventory.application.port.in;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import utp.siga.inventory.application.dto.AssetAdminPage;
import utp.siga.inventory.application.dto.AssetAdminView;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;

public interface AssetAdminUseCase {
    AssetAdminPage list(Filter filter);
    AssetAdminView get(UUID id);
    AssetAdminView register(Register command);
    AssetAdminView update(Update command);
    AssetAdminView changeStatus(ChangeStatus command);

    record Filter(UUID productId, AssetOperationalStatus operationalStatus, AssetAvailability availability,
            UUID locationId, UUID lotId, int page, int size) { }
    record Register(UUID productId, String assetCode, String serialNumber, String brand, String model,
            JsonNode technicalSnapshot, UUID actorUserId, UUID correlationId) { }
    record Update(UUID id, String brand, String model, JsonNode technicalSnapshot, long expectedVersion,
            UUID actorUserId, UUID correlationId) { }
    record ChangeStatus(UUID id, AssetOperationalStatus targetStatus, long expectedVersion,
            UUID actorUserId, UUID correlationId) { }
}
