package utp.siga.inventory.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.dto.AssetAdminPage;
import utp.siga.inventory.application.dto.AssetAdminView;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.port.in.AssetAdminUseCase;
import utp.siga.inventory.application.port.out.AssetStore;
import utp.siga.inventory.application.port.out.ProductRefPort;
import utp.siga.inventory.domain.model.ProductRef;

@Service
public class DefaultAssetAdminUseCase implements AssetAdminUseCase {
    private static final Set<String> FORBIDDEN_TECHNICAL_KEYS = Set.of(
            "locationId", "currentLocationId", "quantity", "stockQuantity", "custodianId",
            "responsibleUserId", "productId", "operationalStatus");
    private final AssetStore assets;
    private final ProductRefPort products;

    public DefaultAssetAdminUseCase(AssetStore assets, ProductRefPort products) {
        this.assets = assets;
        this.products = products;
    }

    @Override public AssetAdminPage list(Filter filter) {
        require(filter, "filter");
        if (filter.page() < 0 || filter.size() < 1 || filter.size() > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        return assets.list(filter);
    }

    @Override public AssetAdminView get(UUID id) { return assets.get(require(id, "id")); }

    @Override
    @Transactional
    public AssetAdminView register(Register command) {
        require(command, "command");
        require(command.productId(), "productId");
        validateText(command.assetCode(), 80, false, "assetCode");
        validateText(command.serialNumber(), 160, false, "serialNumber");
        validateText(command.brand(), 100, true, "brand");
        validateText(command.model(), 100, true, "model");
        validateSnapshot(command.technicalSnapshot());
        require(command.actorUserId(), "actorUserId");
        require(command.correlationId(), "correlationId");
        ProductRef product = products.getProduct(command.productId())
                .orElseThrow(() -> new IllegalArgumentException("productId does not exist"));
        if (!product.active()) throw new IllegalArgumentException("productId must be active");
        if (!product.requiresSerial()) throw new IllegalArgumentException("productId must require serial tracking");
        return assets.register(command);
    }

    @Override
    @Transactional
    public AssetAdminView update(Update command) {
        validateMutation(command == null ? null : command.id(), command == null ? -1 : command.expectedVersion(),
                command == null ? null : command.actorUserId(), command == null ? null : command.correlationId());
        validateText(command.brand(), 100, true, "brand");
        validateText(command.model(), 100, true, "model");
        validateSnapshot(command.technicalSnapshot());
        return assets.update(command);
    }

    @Override
    @Transactional
    public AssetAdminView changeStatus(ChangeStatus command) {
        validateMutation(command == null ? null : command.id(), command == null ? -1 : command.expectedVersion(),
                command == null ? null : command.actorUserId(), command == null ? null : command.correlationId());
        require(command.targetStatus(), "targetStatus");
        if (command.targetStatus() == AssetOperationalStatus.EN_TRANSITO) {
            throw new IllegalArgumentException("EN_TRANSITO is not an administrative target");
        }
        return assets.changeStatus(command);
    }

    private static void validateMutation(UUID id, long version, UUID actor, UUID correlation) {
        require(id, "id");
        if (version < 0) throw new IllegalArgumentException("expectedVersion must be non-negative");
        require(actor, "actorUserId");
        require(correlation, "correlationId");
    }

    private static void validateSnapshot(JsonNode snapshot) {
        require(snapshot, "technicalSnapshot");
        if (!snapshot.isObject()) throw new IllegalArgumentException("technicalSnapshot must be an object");
        FORBIDDEN_TECHNICAL_KEYS.forEach(key -> {
            if (snapshot.has(key)) throw new IllegalArgumentException("technicalSnapshot cannot contain " + key);
        });
    }

    private static void validateText(String value, int max, boolean nullable, String field) {
        if (value == null) {
            if (!nullable) throw new IllegalArgumentException(field + " is required");
            return;
        }
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be nonblank and at most " + max + " characters");
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
