package utp.siga.inventory.application.port.out;

import java.util.UUID;

public interface LocationActivityPort {
    boolean isActive(UUID locationId);
}
