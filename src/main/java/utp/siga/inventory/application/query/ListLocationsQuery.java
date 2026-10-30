package utp.siga.inventory.application.query;

import java.util.List;
import utp.siga.inventory.application.dto.LocationView;

public interface ListLocationsQuery {
    List<LocationView> listActiveLocations();
}
