package utp.siga.inventory.application.usecase;

import java.util.UUID;
import org.springframework.stereotype.Service;
import utp.siga.inventory.application.dto.PhysicalCountPage;
import utp.siga.inventory.application.exception.PhysicalCountValidationException;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.application.port.out.PhysicalCountStore;
import utp.siga.inventory.domain.model.PhysicalCount;

@Service
public class DefaultPhysicalCountUseCase implements PhysicalCountUseCase {
    private final PhysicalCountStore store;

    public DefaultPhysicalCountUseCase(PhysicalCountStore store) {
        this.store = store;
    }

    @Override
    public PhysicalCountPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new PhysicalCountValidationException("VALIDATION_ERROR",
                    "page must be non-negative and size must be between 1 and 100");
        }
        return store.list(page, size);
    }

    @Override public PhysicalCount get(UUID id) { return store.get(id); }
    @Override public PhysicalCount create(Create command) { validate(command.locationId(), command.actorUserId(), command.idempotencyKey()); return store.create(command); }
    @Override public PhysicalCount capture(Capture command) { validate(command.countId(), command.lineId(), command.actorUserId()); return store.capture(command); }
    @Override public PhysicalCount addUnexpected(AddUnexpected command) { validate(command.countId(), command.productId(), command.actorUserId(), command.idempotencyKey()); return store.addUnexpected(command); }
    @Override public PhysicalCount close(Command command) { validate(command.countId(), command.actorUserId(), command.idempotencyKey()); return store.close(command); }
    @Override public PhysicalCount cancel(Cancel command) { validate(command.countId(), command.actorUserId(), command.idempotencyKey()); return store.cancel(command); }

    private static void validate(Object... values) {
        for (Object value : values) {
            if (value == null) throw new PhysicalCountValidationException("VALIDATION_ERROR", "Required value is missing");
        }
    }
}
