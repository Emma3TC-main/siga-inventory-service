package utp.siga.inventory.presentation.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.inventory.application.dto.PhysicalCountPage;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.domain.model.PhysicalCount;
import utp.siga.inventory.domain.model.PhysicalCountLine;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;

@RestController
@RequestMapping("/api/v1/counts")
public class PhysicalCountController {
    private final PhysicalCountUseCase counts;
    private final JwtAuthenticatedActorAdapter actors;

    public PhysicalCountController(PhysicalCountUseCase counts, JwtAuthenticatedActorAdapter actors) {
        this.counts = counts;
        this.actors = actors;
    }

    @GetMapping
    public ResponseEntity<PageResponse> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PhysicalCountPage result = counts.list(page, size);
        return ResponseEntity.ok(new PageResponse(result.content().stream().map(Response::from).toList(),
                result.page(), result.size(), result.totalElements()));
    }

    @PostMapping
    public ResponseEntity<Response> create(@Valid @RequestBody CreateRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        PhysicalCount created = counts.create(new PhysicalCountUseCase.Create(
                request.locationId(), request.notes(), actors.actorUserId(jwt), idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Response> get(@PathVariable UUID id) {
        return ResponseEntity.ok(Response.from(counts.get(id)));
    }

    @PutMapping("/{id}/lines/{lineId}")
    public ResponseEntity<Response> capture(@PathVariable UUID id, @PathVariable UUID lineId,
            @Valid @RequestBody CaptureRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Response.from(counts.capture(new PhysicalCountUseCase.Capture(
                id, lineId, request.expectedVersion(), request.countedQuantity(), request.notes(),
                actors.actorUserId(jwt)))));
    }

    @PostMapping("/{id}/lines")
    public ResponseEntity<Response> addUnexpected(@PathVariable UUID id,
            @Valid @RequestBody UnexpectedLineRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Response.from(counts.addUnexpected(new PhysicalCountUseCase.AddUnexpected(
                id, request.expectedVersion(), request.productId(), request.lotId(), request.assetId(),
                request.countedQuantity(), request.notes(), actors.actorUserId(jwt), idempotencyKey))));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Response> close(@PathVariable UUID id, @Valid @RequestBody CommandRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Response.from(counts.close(new PhysicalCountUseCase.Command(
                id, request.expectedVersion(), actors.actorUserId(jwt), idempotencyKey))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Response> cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Response.from(counts.cancel(new PhysicalCountUseCase.Cancel(
                id, request.expectedVersion(), request.reason(), actors.actorUserId(jwt), idempotencyKey))));
    }

    public record CreateRequest(@NotNull UUID locationId, @Size(max = 2000) String notes) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown physical count field: " + name);
        }
    }

    public record CaptureRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @DecimalMin("0") BigDecimal countedQuantity, @Size(max = 2000) String notes) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown physical count line field: " + name);
        }
    }

    public record UnexpectedLineRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull UUID productId, UUID lotId, UUID assetId,
            @NotNull @DecimalMin("0") BigDecimal countedQuantity, @Size(max = 2000) String notes) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown physical count line field: " + name);
        }
    }

    public record CommandRequest(@NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown physical count command field: " + name);
        }
    }

    public record CancelRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 255) String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown physical count command field: " + name);
        }
    }

    public record LineResponse(UUID id, UUID productId, UUID lotId, UUID assetId,
            BigDecimal systemQuantity, BigDecimal countedQuantity, BigDecimal differenceQuantity,
            String notes, UUID countedByUserId, Instant countedAt) {
        static LineResponse from(PhysicalCountLine line) {
            return new LineResponse(line.id(), line.productId(), line.lotId(), line.assetId(),
                    line.systemQuantity(), line.countedQuantity(), line.differenceQuantity(), line.notes(),
                    line.countedByUserId(), line.countedAt());
        }
    }

    public record Response(UUID id, UUID locationId, String status, long version, String notes,
            UUID createdByUserId, Instant startedAt, Instant completedAt, UUID completedByUserId,
            Instant cancelledAt, UUID cancelledByUserId, String cancellationReason,
            Instant createdAt, Instant updatedAt, List<LineResponse> lines) {
        static Response from(PhysicalCount count) {
            return new Response(count.id(), count.locationId(), count.status().name(), count.version(),
                    count.notes(), count.createdByUserId(), count.startedAt(), count.completedAt(),
                    count.completedByUserId(), count.cancelledAt(), count.cancelledByUserId(),
                    count.cancellationReason(), count.createdAt(), count.updatedAt(),
                    count.lines().stream().map(LineResponse::from).toList());
        }
    }

    public record PageResponse(List<Response> content, int page, int size, long totalElements) { }
}
