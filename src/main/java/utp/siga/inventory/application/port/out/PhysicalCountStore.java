package utp.siga.inventory.application.port.out;

import java.util.UUID;
import utp.siga.inventory.application.dto.PhysicalCountPage;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.domain.model.PhysicalCount;

public interface PhysicalCountStore {
    PhysicalCountPage list(int page, int size);
    PhysicalCount get(UUID id);
    PhysicalCount create(PhysicalCountUseCase.Create command);
    PhysicalCount capture(PhysicalCountUseCase.Capture command);
    PhysicalCount addUnexpected(PhysicalCountUseCase.AddUnexpected command);
    PhysicalCount close(PhysicalCountUseCase.Command command);
    PhysicalCount cancel(PhysicalCountUseCase.Cancel command);
}
