package utp.siga.inventory.application.port.out;

import java.util.List;
import java.util.UUID;
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.port.in.LocationUseCase;

public interface LocationStore {
    List<LocationView> list(LocationActiveFilter active);
    LocationView get(UUID id);
    LocationView create(LocationUseCase.Create command);
    LocationView update(LocationUseCase.Update command);
    LocationView deactivate(LocationUseCase.Lifecycle command);
    LocationView reactivate(LocationUseCase.Lifecycle command);
}
