package utp.siga.inventory.application.port.out;

import java.util.UUID;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetPage;

public interface AssetQueryPort {
    AssetPage findOperationalAssets(UUID productId, UUID locationId, UUID lotId,
            AssetAvailability availability, int page, int size);
}
