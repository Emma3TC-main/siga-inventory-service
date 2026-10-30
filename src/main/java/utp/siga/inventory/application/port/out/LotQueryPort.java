package utp.siga.inventory.application.port.out;

import java.time.LocalDate;
import java.util.UUID;
import utp.siga.inventory.application.dto.LotPage;

public interface LotQueryPort {
    LotPage findOperationalLots(UUID productId, UUID locationId, boolean availableOnly,
            LocalDate currentDate, int page, int size);
}
