package utp.siga.inventory.application.usecase;

import java.util.List;
import org.springframework.stereotype.Service;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.port.out.LocationQueryPort;
import utp.siga.inventory.application.query.ListLocationsQuery;

@Service
public class DefaultListLocationsQuery implements ListLocationsQuery {

    private final LocationQueryPort locations;

    public DefaultListLocationsQuery(LocationQueryPort locations) {
        this.locations = locations;
    }

    @Override
    public List<LocationView> listActiveLocations() {
        return locations.findActiveLocations();
    }
}
