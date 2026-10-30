package utp.siga.inventory.presentation.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.inventory.application.dto.AssetAdminPage;
import utp.siga.inventory.application.dto.AssetAdminView;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.port.in.AssetAdminUseCase;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;

@RestController
@RequestMapping("/api/v1")
public class AssetAdminController {
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "brand", "model", "technicalSnapshot", "expectedVersion");
    private final AssetAdminUseCase assets;
    private final JwtAuthenticatedActorAdapter actors;

    public AssetAdminController(AssetAdminUseCase assets, JwtAuthenticatedActorAdapter actors) {
        this.assets = assets;
        this.actors = actors;
    }

    @GetMapping("/admin/assets")
    public ResponseEntity<AssetAdminPage> list(@RequestParam(required = false) UUID productId,
            @RequestParam(required = false) AssetOperationalStatus operationalStatus,
            @RequestParam(required = false) AssetAvailability availability,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID lotId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(assets.list(new AssetAdminUseCase.Filter(
                productId, operationalStatus, availability, locationId, lotId, page, size)));
    }

    @GetMapping("/assets/{id}")
    public ResponseEntity<AssetAdminView> get(@PathVariable UUID id) {
        return ResponseEntity.ok(assets.get(id));
    }

    @PostMapping("/assets")
    public ResponseEntity<AssetAdminView> register(@Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        AssetAdminView result = assets.register(new AssetAdminUseCase.Register(request.productId(),
                request.assetCode(), request.serialNumber(), request.brand(), request.model(),
                request.technicalSnapshot(), actors.actorUserId(jwt), correlationId(http)));
        return ResponseEntity.created(URI.create("/api/v1/assets/" + result.id())).body(result);
    }

    @PutMapping("/assets/{id}")
    public ResponseEntity<AssetAdminView> update(@PathVariable UUID id, @RequestBody JsonNode request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        requireExactFields(request, UPDATE_FIELDS);
        return ResponseEntity.ok(assets.update(new AssetAdminUseCase.Update(id,
                nullableText(request, "brand"), nullableText(request, "model"),
                requiredObject(request, "technicalSnapshot"), requiredVersion(request, "expectedVersion"),
                actors.actorUserId(jwt), correlationId(http))));
    }

    @PostMapping("/assets/{id}/status")
    public ResponseEntity<AssetAdminView> status(@PathVariable UUID id,
            @Valid @RequestBody StatusRequest request, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {
        return ResponseEntity.ok(assets.changeStatus(new AssetAdminUseCase.ChangeStatus(id,
                request.targetStatus(), request.expectedVersion(), actors.actorUserId(jwt), correlationId(http))));
    }

    private static void requireExactFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Request body must be an object");
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!fields.contains(name)) throw new IllegalArgumentException("Unknown asset field: " + name);
        }
        for (String field : fields) if (!node.has(field)) throw new IllegalArgumentException(field + " is required");
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text or null");
        return value.textValue();
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private static long requiredVersion(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (!value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static UUID correlationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-ID");
        if (header != null) {
            try { return UUID.fromString(header); } catch (IllegalArgumentException ignored) { }
        }
        return UUID.randomUUID();
    }

    public record CreateRequest(@NotNull UUID productId,
            @NotBlank @Size(max = 80) String assetCode,
            @NotBlank @Size(max = 160) String serialNumber,
            @Size(max = 100) String brand,
            @Size(max = 100) String model,
            @NotNull JsonNode technicalSnapshot) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown asset field: " + name);
        }
    }

    public record StatusRequest(@NotNull AssetOperationalStatus targetStatus,
            @NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown asset status field: " + name);
        }
    }
}
