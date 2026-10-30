package utp.siga.inventory.application.usecase;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import utp.siga.inventory.application.dto.LotPage;
import utp.siga.inventory.application.port.out.LotQueryPort;
import utp.siga.inventory.application.query.ListLotsQuery;

@Service
public class DefaultListLotsQuery implements ListLotsQuery {

    private final LotQueryPort lots;
    private final Clock clock;

    public DefaultListLotsQuery(LotQueryPort lots, Clock clock) {
        this.lots = lots;
        this.clock = clock;
    }

    @Override
    public LotPage listLots(UUID productId, UUID locationId, boolean availableOnly, int page, int size) {
        requireProduct(productId);
        validatePage(page, size);
        return lots.findOperationalLots(productId, locationId, availableOnly,
                LocalDate.now(clock), page, size);
    }

    static void requireProduct(UUID productId) {
        if (productId == null) throw new IllegalArgumentException("productId is required");
    }

    static void validatePage(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be at least 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
    }
}
