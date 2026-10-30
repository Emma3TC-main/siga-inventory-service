package utp.siga.inventory.presentation.rest;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.inventory.application.dto.InventoryPage;
import utp.siga.inventory.application.query.GetStockQuery;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockController {

    private final GetStockQuery query;

    public StockController(GetStockQuery query) {
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<InventoryPage> getStock(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID lotId,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        InventoryPage result = query.getStock(productId, locationId, lotId, assetId, page, size);
        return ResponseEntity.ok(result);
    }
}
