package utp.siga.inventory.application.usecase;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.port.out.EventIdempotencyPort;
import utp.siga.inventory.application.port.out.ProductRefPort;
import utp.siga.inventory.application.port.out.SupplierRefPort;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.SupplierRef;

@Service
public class CatalogProjectionService implements SyncCatalogProjectionUseCase {

    private final EventIdempotencyPort idempotency;
    private final SupplierRefPort suppliers;
    private final ProductRefPort products;

    public CatalogProjectionService(EventIdempotencyPort idempotency, SupplierRefPort suppliers, ProductRefPort products) {
        this.idempotency = idempotency;
        this.suppliers = suppliers;
        this.products = products;
    }

    @Override
    @Transactional
    public void syncSupplier(UUID eventId, SupplierRef supplier) {
        if (!idempotency.claim(eventId, "inventory-catalog-sync")) return;
        suppliers.saveIfNewer(supplier);
    }

    @Override
    @Transactional
    public void syncProduct(UUID eventId, ProductRef product) {
        if (!idempotency.claim(eventId, "inventory-catalog-sync")) return;
        products.saveIfNewer(product);
    }
}
