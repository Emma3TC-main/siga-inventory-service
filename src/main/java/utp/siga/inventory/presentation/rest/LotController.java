package utp.siga.inventory.presentation.rest;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.inventory.application.dto.LotPage;
import utp.siga.inventory.application.query.ListLotsQuery;

@RestController
@RequestMapping("/api/v1/lots")
public class LotController {

    private final ListLotsQuery lots;

    public LotController(ListLotsQuery lots) {
        this.lots = lots;
    }

    @GetMapping
    public ResponseEntity<LotPage> getLots(
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "false") boolean availableOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(lots.listLots(productId, locationId, availableOnly, page, size));
    }
}
