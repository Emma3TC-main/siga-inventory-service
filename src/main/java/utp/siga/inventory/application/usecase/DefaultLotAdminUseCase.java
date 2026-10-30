package utp.siga.inventory.application.usecase;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.dto.LotAdminPage;
import utp.siga.inventory.application.dto.LotAdminView;
import utp.siga.inventory.application.port.in.LotAdminUseCase;
import utp.siga.inventory.application.port.out.LotStore;
import utp.siga.inventory.application.port.out.ProductRefPort;
import utp.siga.inventory.application.dto.LotStatus;
import utp.siga.inventory.domain.model.ProductRef;

@Service
public class DefaultLotAdminUseCase implements LotAdminUseCase {
    private final LotStore lots;
    private final ProductRefPort products;
    private final Clock clock;

    public DefaultLotAdminUseCase(LotStore lots, ProductRefPort products, Clock clock) {
        this.lots = lots;
        this.products = products;
        this.clock = clock;
    }

    @Override public LotAdminPage list(Filter filter) {
        require(filter, "filter");
        if (filter.page() < 0 || filter.size() < 1 || filter.size() > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        return lots.list(filter, today());
    }

    @Override public LotAdminView get(UUID id) { return lots.get(require(id, "id"), today()); }

    @Override
    @Transactional
    public LotAdminView create(Create command) {
        require(command, "command");
        require(command.productId(), "productId");
        validateIdentity(command.lotNumber(), 120, "lotNumber");
        validateIdentity(command.heatNumber(), 120, "heatNumber");
        validateDates(command.manufacturedAt(), command.expiresAt());
        require(command.actorUserId(), "actorUserId");
        require(command.correlationId(), "correlationId");
        ProductRef product = products.getProduct(command.productId())
                .orElseThrow(() -> new IllegalArgumentException("productId does not exist"));
        if (!product.active()) throw new IllegalArgumentException("productId must be active");
        if (product.requiresLot() && command.lotNumber() == null) {
            throw new IllegalArgumentException("lotNumber is required for this product");
        }
        if (product.requiresHeatNumber() && command.heatNumber() == null) {
            throw new IllegalArgumentException("heatNumber is required for this product");
        }
        if (product.requiresExpiry() && command.expiresAt() == null) {
            throw new IllegalArgumentException("expiresAt is required for this product");
        }
        return lots.create(command, today());
    }

    @Override
    @Transactional
    public LotAdminView update(Update command) {
        validateMutation(command == null ? null : command.id(), command == null ? -1 : command.expectedVersion(),
                command == null ? null : command.actorUserId(), command == null ? null : command.correlationId());
        validateDates(command.manufacturedAt(), command.expiresAt());
        return lots.update(command, today());
    }

    @Override
    @Transactional
    public LotAdminView changeStatus(ChangeStatus command) {
        validateMutation(command == null ? null : command.id(), command == null ? -1 : command.expectedVersion(),
                command == null ? null : command.actorUserId(), command == null ? null : command.correlationId());
        require(command.targetStatus(), "targetStatus");
        if (command.targetStatus() == LotStatus.EXPIRED || command.targetStatus() == LotStatus.DEPLETED) {
            throw new IllegalArgumentException("targetStatus is not a manual status");
        }
        return lots.changeStatus(command, today());
    }

    private void validateMutation(UUID id, long expectedVersion, UUID actor, UUID correlation) {
        require(id, "id");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must be non-negative");
        require(actor, "actorUserId");
        require(correlation, "correlationId");
    }

    private static void validateDates(LocalDate manufacturedAt, LocalDate expiresAt) {
        if (manufacturedAt != null && expiresAt != null && expiresAt.isBefore(manufacturedAt)) {
            throw new IllegalArgumentException("expiresAt must not be before manufacturedAt");
        }
    }

    private static void validateIdentity(String value, int max, String field) {
        if (value != null && (value.isBlank() || value.length() > max)) {
            throw new IllegalArgumentException(field + " must be null or nonblank and at most " + max + " characters");
        }
    }

    private LocalDate today() { return LocalDate.now(clock); }
    private static <T> T require(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
