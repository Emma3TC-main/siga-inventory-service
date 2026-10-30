package utp.siga.inventory.application.port.out;
import java.util.Optional;
import java.util.UUID;
import utp.siga.inventory.domain.model.ProductRef;

public interface ProductRefPort {
    void saveIfNewer(ProductRef product);
    Optional<ProductRef> getProduct(UUID productId);
}
