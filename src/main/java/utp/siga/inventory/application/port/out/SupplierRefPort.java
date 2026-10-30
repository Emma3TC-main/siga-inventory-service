package utp.siga.inventory.application.port.out;
import java.util.Optional;
import java.util.UUID;
import utp.siga.inventory.domain.model.SupplierRef;

public interface SupplierRefPort {
    void saveIfNewer(SupplierRef supplier);
    Optional<SupplierRef> getSupplier(UUID supplierId);
}
