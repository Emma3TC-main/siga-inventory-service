package utp.siga.inventory.application.usecase;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.port.in.LocationUseCase;
import utp.siga.inventory.application.port.out.LocationStore;

@Service
public class DefaultLocationUseCase implements LocationUseCase {
    private final LocationStore store;

    public DefaultLocationUseCase(LocationStore store) {
        this.store = store;
    }

    @Override public List<LocationView> list(LocationActiveFilter active) { return store.list(active); }
    @Override public LocationView get(UUID id) { return store.get(require(id, "id")); }

    @Override
    public LocationView create(Create command) {
        require(command, "command");
        validateText(command.code(), 80, "code");
        validateText(command.name(), 160, "name");
        require(command.locationType(), "locationType");
        require(command.actorUserId(), "actorUserId");
        require(command.correlationId(), "correlationId");
        return store.create(command);
    }

    @Override
    public LocationView update(Update command) {
        require(command, "command");
        require(command.id(), "id");
        validateText(command.name(), 160, "name");
        require(command.locationType(), "locationType");
        requireVersion(command.expectedVersion());
        require(command.actorUserId(), "actorUserId");
        require(command.correlationId(), "correlationId");
        return store.update(command);
    }

    @Override public LocationView deactivate(Lifecycle command) { validate(command); return store.deactivate(command); }
    @Override public LocationView reactivate(Lifecycle command) { validate(command); return store.reactivate(command); }

    private static void validate(Lifecycle command) {
        require(command, "command");
        require(command.id(), "id");
        requireVersion(command.expectedVersion());
        require(command.actorUserId(), "actorUserId");
        require(command.correlationId(), "correlationId");
    }

    private static void validateText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be nonblank and at most " + max + " characters");
        }
    }

    private static void requireVersion(long version) {
        if (version < 0) throw new IllegalArgumentException("expectedVersion must be non-negative");
    }

    private static <T> T require(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
