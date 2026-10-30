package utp.siga.inventory.presentation.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import utp.siga.inventory.application.dto.LotAdminPage;
import utp.siga.inventory.application.dto.LotAdminView;
import utp.siga.inventory.application.dto.LotStatus;
import utp.siga.inventory.application.port.in.LotAdminUseCase;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;

@RestController
@RequestMapping("/api/v1")
public class LotAdminController {
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "manufacturedAt", "receivedAt", "expiresAt", "expectedVersion");
    private final LotAdminUseCase lots;
    private final JwtAuthenticatedActorAdapter actors;

    public LotAdminController(LotAdminUseCase lots, JwtAuthenticatedActorAdapter actors) {
        this.lots = lots;
        this.actors = actors;
    }

    @GetMapping("/admin/lots")
    public ResponseEntity<LotAdminPage> list(@RequestParam(required = false) UUID productId,
            @RequestParam(required = false) LotStatus status,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(lots.list(new LotAdminUseCase.Filter(
                productId, status, expired, locationId, page, size)));
    }

    @GetMapping("/lots/{id}")
    public ResponseEntity<LotAdminView> get(@PathVariable UUID id) {
        return ResponseEntity.ok(lots.get(id));
    }

    @PostMapping("/lots")
    public ResponseEntity<LotAdminView> create(@Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        LotAdminView result = lots.create(new LotAdminUseCase.Create(request.productId(), request.lotNumber(),
                request.heatNumber(), request.manufacturedAt(), request.receivedAt(), request.expiresAt(),
                actors.actorUserId(jwt), correlationId(http)));
        return ResponseEntity.created(URI.create("/api/v1/lots/" + result.id())).body(result);
    }

    @PutMapping("/lots/{id}")
    public ResponseEntity<LotAdminView> update(@PathVariable UUID id, @RequestBody JsonNode request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        requireExactFields(request, UPDATE_FIELDS);
        return ResponseEntity.ok(lots.update(new LotAdminUseCase.Update(id,
                nullableDate(request, "manufacturedAt"), nullableDate(request, "receivedAt"),
                nullableDate(request, "expiresAt"), requiredVersion(request, "expectedVersion"),
                actors.actorUserId(jwt), correlationId(http))));
    }

    @PostMapping("/lots/{id}/status")
    public ResponseEntity<LotAdminView> status(@PathVariable UUID id,
            @Valid @RequestBody StatusRequest request, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {
        return ResponseEntity.ok(lots.changeStatus(new LotAdminUseCase.ChangeStatus(id, request.targetStatus(),
                request.expectedVersion(), actors.actorUserId(jwt), correlationId(http))));
    }

    private static void requireExactFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Request body must be an object");
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!fields.contains(name)) throw new IllegalArgumentException("Unknown lot field: " + name);
        }
        for (String field : fields) if (!node.has(field)) throw new IllegalArgumentException(field + " is required");
    }

    private static LocalDate nullableDate(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be an ISO date or null");
        try { return LocalDate.parse(value.textValue()); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException(field + " must be an ISO date"); }
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
            @Size(max = 120) String lotNumber, @Size(max = 120) String heatNumber,
            LocalDate manufacturedAt, LocalDate receivedAt, LocalDate expiresAt) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown lot field: " + name);
        }
    }

    public record StatusRequest(@NotNull LotStatus targetStatus,
            @NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown lot status field: " + name);
        }
    }
}
