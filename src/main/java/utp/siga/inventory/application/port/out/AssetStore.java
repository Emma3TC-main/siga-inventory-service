package utp.siga.inventory.application.port.out;

import java.util.UUID;
import utp.siga.inventory.application.dto.AssetAdminPage;
import utp.siga.inventory.application.dto.AssetAdminView;
import utp.siga.inventory.application.port.in.AssetAdminUseCase;

public interface AssetStore {
    AssetAdminPage list(AssetAdminUseCase.Filter filter);
    AssetAdminView get(UUID id);
    AssetAdminView register(AssetAdminUseCase.Register command);
    AssetAdminView update(AssetAdminUseCase.Update command);
    AssetAdminView changeStatus(AssetAdminUseCase.ChangeStatus command);
}
