package utp.siga.inventory.application.query;

import java.util.UUID;
import utp.siga.inventory.application.dto.LotPage;

public interface ListLotsQuery {
    LotPage listLots(UUID productId, UUID locationId, boolean availableOnly, int page, int size);
}
