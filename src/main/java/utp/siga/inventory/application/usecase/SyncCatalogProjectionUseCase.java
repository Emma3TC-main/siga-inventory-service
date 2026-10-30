package utp.siga.inventory.application.usecase;

import java.util.UUID;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.SupplierRef;

public interface SyncCatalogProjectionUseCase {
    void syncSupplier(UUID eventId, SupplierRef supplier);
    void syncProduct(UUID eventId, ProductRef product);
}
