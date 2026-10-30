package utp.siga.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetPage;
import utp.siga.inventory.application.dto.LotPage;
import utp.siga.inventory.application.port.out.AssetQueryPort;
import utp.siga.inventory.application.port.out.LotQueryPort;

class DefaultTraceabilityQueriesTest {

    private static final UUID PRODUCT_ID = UUID.fromString("22000000-0000-0000-0000-000000000001");

    @Test
    void lotsUseTheUtcBusinessDateAndDelegateFilters() {
        LotQueryPort port = mock(LotQueryPort.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T23:30:00Z"), ZoneOffset.UTC);
        LotPage expected = new LotPage(List.of(), 1, 25, 0);
        when(port.findOperationalLots(PRODUCT_ID, null, true, LocalDate.parse("2026-10-06"), 1, 25))
                .thenReturn(expected);

        LotPage actual = new DefaultListLotsQuery(port, clock)
                .listLots(PRODUCT_ID, null, true, 1, 25);

        assertSame(expected, actual);
        verify(port).findOperationalLots(PRODUCT_ID, null, true,
                LocalDate.parse("2026-10-06"), 1, 25);
    }

    @Test
    void lotsRejectInvalidProductAndPaginationBeforeCallingPersistence() {
        LotQueryPort port = mock(LotQueryPort.class);
        DefaultListLotsQuery query = new DefaultListLotsQuery(port, Clock.systemUTC());

        assertThrows(IllegalArgumentException.class, () -> query.listLots(null, null, false, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> query.listLots(PRODUCT_ID, null, false, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> query.listLots(PRODUCT_ID, null, false, 0, 101));
    }

    @Test
    void assetsDelegateTheRequiredAvailabilityAndFilters() {
        AssetQueryPort port = mock(AssetQueryPort.class);
        UUID locationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        AssetPage expected = new AssetPage(List.of(), 0, 10, 0);
        when(port.findOperationalAssets(PRODUCT_ID, locationId, lotId,
                AssetAvailability.IN_STOCK, 0, 10)).thenReturn(expected);

        AssetPage actual = new DefaultListAssetsQuery(port)
                .listAssets(PRODUCT_ID, locationId, lotId, AssetAvailability.IN_STOCK, 0, 10);

        assertSame(expected, actual);
        verify(port).findOperationalAssets(PRODUCT_ID, locationId, lotId,
                AssetAvailability.IN_STOCK, 0, 10);
    }

    @Test
    void assetsRejectMissingAvailabilityAndInvalidPagination() {
        AssetQueryPort port = mock(AssetQueryPort.class);
        DefaultListAssetsQuery query = new DefaultListAssetsQuery(port);

        assertThrows(IllegalArgumentException.class,
                () -> query.listAssets(null, null, null, AssetAvailability.UNASSIGNED, 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> query.listAssets(PRODUCT_ID, null, null, null, 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> query.listAssets(PRODUCT_ID, null, null, AssetAvailability.UNASSIGNED, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> query.listAssets(PRODUCT_ID, null, null, AssetAvailability.UNASSIGNED, -1, 20));
    }
}
