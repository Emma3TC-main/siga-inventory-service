package utp.siga.inventory.application.port.in;

import java.util.List;
import java.util.UUID;
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.dto.LocationView;

public interface LocationUseCase {
    List<LocationView> list(LocationActiveFilter active);
    LocationView get(UUID id);
    LocationView create(Create command);
    LocationView update(Update command);
    LocationView deactivate(Lifecycle command);
    LocationView reactivate(Lifecycle command);

    record Create(String code, String name, LocationType locationType, UUID parentId,
            UUID actorUserId, UUID correlationId) { }

    record Update(UUID id, String name, LocationType locationType, UUID parentId,
            long expectedVersion, UUID actorUserId, UUID correlationId) { }

    record Lifecycle(UUID id, long expectedVersion, UUID actorUserId, UUID correlationId) { }
}
