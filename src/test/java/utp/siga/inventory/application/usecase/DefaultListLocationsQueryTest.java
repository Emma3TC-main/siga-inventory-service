package utp.siga.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.port.out.LocationQueryPort;

class DefaultListLocationsQueryTest {

    @Test
    void delegatesActiveLocationLookupToPort() {
        LocationQueryPort port = mock(LocationQueryPort.class);
        List<LocationView> expected = List.of();
        when(port.findActiveLocations()).thenReturn(expected);

        List<LocationView> result = new DefaultListLocationsQuery(port).listActiveLocations();

        assertSame(expected, result);
        verify(port).findActiveLocations();
    }
}
