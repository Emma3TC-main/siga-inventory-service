package utp.siga.inventory.presentation.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
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
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.port.in.LocationUseCase;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {
    private final LocationUseCase locations;
    private final JwtAuthenticatedActorAdapter actors;

    public LocationController(LocationUseCase locations, JwtAuthenticatedActorAdapter actors) {
        this.locations = locations;
        this.actors = actors;
    }

    @GetMapping
    public ResponseEntity<List<LocationView>> list(@RequestParam(defaultValue = "true") String active) {
        return ResponseEntity.ok(locations.list(LocationActiveFilter.parse(active)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationView> get(@PathVariable UUID id) {
        return ResponseEntity.ok(locations.get(id));
    }

    @PostMapping
    public ResponseEntity<LocationView> create(@Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        LocationView result = locations.create(new LocationUseCase.Create(request.code(), request.name(),
                request.locationType(), request.parentId(), actors.actorUserId(jwt), correlationId(http)));
        return ResponseEntity.created(URI.create("/api/v1/locations/" + result.id())).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<LocationView> update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return ResponseEntity.ok(locations.update(new LocationUseCase.Update(id, request.name(),
                request.locationType(), request.parentId(), request.expectedVersion(), actors.actorUserId(jwt),
                correlationId(http))));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<LocationView> deactivate(@PathVariable UUID id,
            @Valid @RequestBody VersionRequest request, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {
        return ResponseEntity.ok(locations.deactivate(new LocationUseCase.Lifecycle(id, request.expectedVersion(),
                actors.actorUserId(jwt), correlationId(http))));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<LocationView> reactivate(@PathVariable UUID id,
            @Valid @RequestBody VersionRequest request, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {
        return ResponseEntity.ok(locations.reactivate(new LocationUseCase.Lifecycle(id, request.expectedVersion(),
                actors.actorUserId(jwt), correlationId(http))));
    }

    private UUID correlationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-ID");
        if (header != null) {
            try { return UUID.fromString(header); } catch (IllegalArgumentException ignored) { }
        }
        return UUID.randomUUID();
    }

    public record CreateRequest(@NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name, @NotNull LocationType locationType, UUID parentId) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown location field: " + name);
        }
    }

    public record UpdateRequest(@NotBlank @Size(max = 160) String name,
            @NotNull LocationType locationType, UUID parentId,
            @NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown location field: " + name);
        }
    }

    public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown location command field: " + name);
        }
    }
}
