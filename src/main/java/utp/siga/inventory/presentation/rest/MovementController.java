package utp.siga.inventory.presentation.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.inventory.application.command.AuthorizeMovementCommand;
import utp.siga.inventory.application.command.CancelMovementCommand;
import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.RejectMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.dto.MovementDetailResponse;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.dto.MovementResponse;
import utp.siga.inventory.application.port.in.MovementUseCase;
import utp.siga.inventory.application.query.GetMovementQuery;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementDetail;
import utp.siga.inventory.domain.model.MovementType;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;
import utp.siga.inventory.infrastructure.security.JwtStepUpValidator;
import utp.siga.inventory.presentation.error.MovementNotFoundException;
import utp.siga.inventory.presentation.error.Problems;

@RestController
@RequestMapping("/api/v1/movements")
public class MovementController {

    private final GetMovementQuery query;
    private final MovementUseCase movements;
    private final JwtAuthenticatedActorAdapter actorAdapter;
    private final JwtStepUpValidator stepUpValidator;

    public MovementController(GetMovementQuery query, MovementUseCase movements,
            JwtAuthenticatedActorAdapter actorAdapter, JwtStepUpValidator stepUpValidator) {
        this.query = query;
        this.movements = movements;
        this.actorAdapter = actorAdapter;
        this.stepUpValidator = stepUpValidator;
    }

    @GetMapping
    public ResponseEntity<MovementPage> getMovements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(query.getMovements(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMovement(@PathVariable UUID id, HttpServletRequest request) {
        return query.getMovement(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(Problems.body(request, 404, "MOVEMENT_NOT_FOUND", "Movimiento no encontrado")));
    }

    @PostMapping
    public ResponseEntity<MovementResponse> create(@Valid @RequestBody MovementRequest request,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication, HttpServletRequest httpRequest) {
        httpRequest.setAttribute("siga.inventory.movementType", request.movementType().name());
        requireMovementPermission(request.movementType(), authentication);
        Movement created = movements.createMovement(request.toCommand(), actorAdapter.actorUserId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<MovementResponse> submit(@PathVariable UUID id,
            HttpServletRequest httpRequest,
            JwtAuthenticationToken authentication) {
        requireExistingMovementPermission(id, authentication);
        return ResponseEntity.ok(toResponse(movements.submitMovement(
                new SubmitMovementCommand(id, "Bearer " + authentication.getToken().getTokenValue(),
                        resolveCorrelationId(httpRequest.getHeader("X-Correlation-ID"))),
                hasAuthority(authentication, "MOVEMENT_AUTHORIZE"))));
    }

    @PostMapping("/{id}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable UUID id,
            @Valid @RequestBody AuthorizationDecision request, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = verifiedAuthorizer(jwt);
        movements.authorizeMovement(new AuthorizeMovementCommand(id, request.reason()), actor, true);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
            @Valid @RequestBody AuthorizationDecision request, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = verifiedAuthorizer(jwt);
        movements.rejectMovement(new RejectMovementCommand(id, request.reason()), actor, true);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, Authentication authentication) {
        requireExistingMovementPermission(id, authentication);
        movements.cancelMovement(new CancelMovementCommand(id));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<MovementResponse> confirm(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        requireExistingMovementPermission(id, authentication);
        ConfirmMovementCommand command = new ConfirmMovementCommand(id, idempotencyKey,
                actorAdapter.actorUserId(jwt), resolveCorrelationId(correlationId),
                hasAuthority(authentication, "MOVEMENT_AUTHORIZE"));
        Movement confirmed = movements.confirmMovement(command);
        return ResponseEntity.ok(query.getMovement(confirmed.getId()).orElseGet(() -> toResponse(confirmed)));
    }

    private UUID verifiedAuthorizer(Jwt jwt) {
        try {
            return stepUpValidator.verifiedAuthorizer(jwt);
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Valid recent MFA step-up is required", exception);
        }
    }

    private void requireExistingMovementPermission(UUID id, Authentication authentication) {
        MovementResponse movement = query.getMovement(id).orElseThrow(MovementNotFoundException::new);
        requireMovementPermission(MovementType.valueOf(movement.movementType()), authentication);
    }

    private void requireMovementPermission(MovementType type, Authentication authentication) {
        String required = type == MovementType.ADJUST_POSITIVE || type == MovementType.ADJUST_NEGATIVE
                ? "INVENTORY_ADJUST" : "MOVEMENT_CREATE";
        boolean allowed = hasAuthority(authentication, required);
        if (!allowed) {
            throw new AccessDeniedException(required + " permission is required");
        }
    }

    private boolean hasAuthority(Authentication authentication, String permission) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private UUID resolveCorrelationId(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // The service still supplies a traceable UUID for direct clients that bypass the Gateway.
            }
        }
        return UUID.randomUUID();
    }

    private static MovementResponse toResponse(Movement movement) {
        return new MovementResponse(
                movement.getId(), movement.getMovementCode(), movement.getType().name(),
                movement.getStatus().name(), movement.getRegisteredByUserId(), movement.getRequestedByUserId(),
                movement.getCostCenterCode(), movement.getCostCenterNameSnapshot(), movement.getSupplierId(),
                movement.getSupplierNameSnapshot(), movement.getSupplierTaxIdSnapshot(), movement.isSensitive(),
                movement.getSensitivityReason(), movement.getDetails().stream().map(MovementController::toResponse).toList(),
                movement.getCreatedAt(), movement.getSubmittedAt(), movement.getConfirmedAt());
    }

    private static MovementDetailResponse toResponse(MovementDetail detail) {
        return new MovementDetailResponse(
                detail.getId(), detail.getLineNumber(), detail.getProductId(), detail.getEnteredQuantity(),
                detail.getUnitCode(), detail.getConversionFactor(), detail.getStockQuantity(),
                detail.getOriginLocationId(), detail.getDestinationLocationId(), detail.getLotId(),
                detail.getAssetId(), detail.getOriginalCurrency(), detail.getOriginalUnitCost(),
                detail.getExchangeRate(), detail.getExchangeRateDate(), detail.getExchangeRateSource(), null, null);
    }

    public record MovementRequest(
            @NotNull MovementType movementType,
            UUID requestedByUserId,
            String costCenterCode,
            String costCenterNameSnapshot,
            UUID supplierId,
            String referenceDocType,
            String referenceDocNumber,
            String reason,
            String notes,
            @NotEmpty List<@Valid MovementDetailRequest> details) {

        CreateMovementCommand toCommand() {
            return new CreateMovementCommand(movementType, requestedByUserId, costCenterCode,
                    costCenterNameSnapshot, supplierId, referenceDocType, referenceDocNumber, reason, notes,
                    details.stream().map(MovementDetailRequest::toCommand).toList());
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown movement request field: " + name);
        }
    }

    public record MovementDetailRequest(
            @NotNull UUID productId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal enteredQuantity,
            @NotBlank String unitCode,
            UUID originLocationId,
            UUID destinationLocationId,
            UUID lotId,
            UUID assetId,
            @Size(min = 3, max = 3) String originalCurrency,
            @DecimalMin("0") BigDecimal originalUnitCost,
            @DecimalMin(value = "0", inclusive = false) BigDecimal exchangeRate,
            LocalDate exchangeRateDate,
            String exchangeRateSource) {

        CreateMovementCommand.Detail toCommand() {
            return new CreateMovementCommand.Detail(productId, enteredQuantity, unitCode, originLocationId,
                    destinationLocationId, lotId, assetId, originalCurrency, originalUnitCost, exchangeRate,
                    exchangeRateDate, exchangeRateSource);
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown movement detail field: " + name);
        }
    }

    public record AuthorizationDecision(String reason) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown authorization field: " + name);
        }
    }
}
