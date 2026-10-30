package utp.siga.inventory.application.usecase;

import java.util.UUID;
import org.springframework.stereotype.Service;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetPage;
import utp.siga.inventory.application.port.out.AssetQueryPort;
import utp.siga.inventory.application.query.ListAssetsQuery;

@Service
public class DefaultListAssetsQuery implements ListAssetsQuery {

    private final AssetQueryPort assets;

    public DefaultListAssetsQuery(AssetQueryPort assets) {
        this.assets = assets;
    }

    @Override
    public AssetPage listAssets(UUID productId, UUID locationId, UUID lotId,
            AssetAvailability availability, int page, int size) {
        DefaultListLotsQuery.requireProduct(productId);
        if (availability == null) throw new IllegalArgumentException("availability is required");
        DefaultListLotsQuery.validatePage(page, size);
        return assets.findOperationalAssets(productId, locationId, lotId, availability, page, size);
    }
}
