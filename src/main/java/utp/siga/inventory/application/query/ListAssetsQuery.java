package utp.siga.inventory.application.query;

import java.util.UUID;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetPage;

public interface ListAssetsQuery {
    AssetPage listAssets(UUID productId, UUID locationId, UUID lotId,
            AssetAvailability availability, int page, int size);
}
