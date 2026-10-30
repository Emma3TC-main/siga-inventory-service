package utp.siga.inventory.application.port.out;

import java.time.LocalDate;
import java.util.UUID;
import utp.siga.inventory.application.dto.LotAdminPage;
import utp.siga.inventory.application.dto.LotAdminView;
import utp.siga.inventory.application.port.in.LotAdminUseCase;

public interface LotStore {
    LotAdminPage list(LotAdminUseCase.Filter filter, LocalDate currentDate);
    LotAdminView get(UUID id, LocalDate currentDate);
    LotAdminView create(LotAdminUseCase.Create command, LocalDate currentDate);
    LotAdminView update(LotAdminUseCase.Update command, LocalDate currentDate);
    LotAdminView changeStatus(LotAdminUseCase.ChangeStatus command, LocalDate currentDate);
}
