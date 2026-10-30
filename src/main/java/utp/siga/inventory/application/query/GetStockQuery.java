package utp.siga.inventory.application.query;

import java.util.UUID;
import utp.siga.inventory.application.dto.InventoryPage;

public interface GetStockQuery {
    InventoryPage getStock(UUID productId, UUID locationId, UUID lotId, UUID assetId, int page, int size);
}
