package utp.siga.inventory.application.port.out;

import java.util.List;
import utp.siga.inventory.application.dto.LocationView;

public interface LocationQueryPort {
    List<LocationView> findActiveLocations();
}
