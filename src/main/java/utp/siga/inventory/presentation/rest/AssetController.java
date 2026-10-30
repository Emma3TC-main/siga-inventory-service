package utp.siga.inventory.presentation.rest;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetPage;
import utp.siga.inventory.application.query.ListAssetsQuery;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final ListAssetsQuery assets;

    public AssetController(ListAssetsQuery assets) {
        this.assets = assets;
    }

    @GetMapping
    public ResponseEntity<AssetPage> getAssets(
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID lotId,
            @RequestParam AssetAvailability availability,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(assets.listAssets(productId, locationId, lotId, availability, page, size));
    }
}
