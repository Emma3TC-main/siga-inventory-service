package utp.siga.inventory.presentation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import utp.siga.inventory.domain.exception.InvalidMovementFieldException;
import utp.siga.inventory.domain.exception.InvalidMovementStateException;
import utp.siga.inventory.application.exception.EvidenceDependencyException;
import utp.siga.inventory.application.exception.EvidenceRequiredException;
import utp.siga.inventory.application.exception.LotUnavailableException;
import utp.siga.inventory.application.exception.AssetUnavailableException;

@RestControllerAdvice(assignableTypes = utp.siga.inventory.presentation.rest.MovementController.class)
public class MovementHttpExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MovementHttpExceptionHandler.class);
    private static final String MOVEMENT_TYPE_ATTRIBUTE = "siga.inventory.movementType";

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            InvalidMovementFieldException.class})
    ResponseEntity<Map<String, Object>> validation(Exception exception, HttpServletRequest request) {
        logValidationFailure(exception, request);
        return problem(request, 400, "VALIDATION_ERROR", "La solicitud de movimiento no es válida");
    }

    @ExceptionHandler(MovementNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(MovementNotFoundException exception, HttpServletRequest request) {
        return problem(request, 404, "MOVEMENT_NOT_FOUND", "Movimiento no encontrado");
    }

    @ExceptionHandler(InvalidMovementStateException.class)
    ResponseEntity<Map<String, Object>> invalidState(InvalidMovementStateException exception,
            HttpServletRequest request) {
        return problem(request, 409, "MOVEMENT_STATE_CONFLICT", "El estado del movimiento no permite la operación");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return problem(request, 403, "AUTH_FORBIDDEN", "Permiso o step-up insuficiente");
    }

    @ExceptionHandler(EvidenceRequiredException.class)
    ResponseEntity<Map<String, Object>> evidenceRequired(EvidenceRequiredException exception,
            HttpServletRequest request) {
        return problem(request, 409, "EVIDENCE_REQUIRED", "El ajuste requiere al menos una evidencia disponible.");
    }

    @ExceptionHandler(EvidenceDependencyException.class)
    ResponseEntity<Map<String, Object>> evidenceUnavailable(EvidenceDependencyException exception,
            HttpServletRequest request) {
        return problem(request, 503, "EVIDENCE_SERVICE_UNAVAILABLE",
                "No se pudo verificar la evidencia del ajuste; reintenta más tarde.");
    }

    @ExceptionHandler(LotUnavailableException.class)
    ResponseEntity<Map<String, Object>> lotUnavailable(LotUnavailableException exception,
            HttpServletRequest request) {
        return problem(request, 409, "LOT_NOT_AVAILABLE", "El lote no está disponible para el movimiento");
    }

    @ExceptionHandler(AssetUnavailableException.class)
    ResponseEntity<Map<String, Object>> assetUnavailable(AssetUnavailableException exception,
            HttpServletRequest request) {
        return problem(request, 409, "ASSET_NOT_AVAILABLE", "El activo no está disponible para el movimiento");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception, HttpServletRequest request) {
        if ("Movement not found".equals(exception.getMessage())) {
            return problem(request, 404, "MOVEMENT_NOT_FOUND", "Movimiento no encontrado");
        }
        logValidationFailure(exception, request);
        return problem(request, 400, "VALIDATION_ERROR", "La solicitud de movimiento no es válida");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception, HttpServletRequest request) {
        return problem(request, 409, "MOVEMENT_CONFLICT", "El movimiento no pudo procesarse por un conflicto");
    }

    private ResponseEntity<Map<String, Object>> problem(HttpServletRequest request, int status, String code,
            String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.body(request, status, code, detail));
    }

    private void logValidationFailure(Exception exception, HttpServletRequest request) {
        String correlationId = Problems.correlationId(request);
        Object movementType = request.getAttribute(MOVEMENT_TYPE_ATTRIBUTE);
        LOGGER.warn("Movement request validation failed correlationId={} movementType={} cause={}",
                correlationId == null || correlationId.isBlank() ? "UNAVAILABLE" : sanitize(correlationId),
                movementType == null ? "UNKNOWN" : sanitize(movementType.toString()),
                diagnosticCause(exception));
    }

    private String diagnosticCause(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException invalid) {
            return invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ":" + error.getCode())
                    .sorted()
                    .collect(Collectors.joining(","));
        }
        if (exception instanceof MissingRequestHeaderException missing) {
            return "missing-header:" + sanitize(missing.getHeaderName());
        }
        if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            return "invalid-parameter:" + sanitize(mismatch.getName());
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return "malformed-request-body";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : sanitize(message);
    }

    private String sanitize(String value) {
        String sanitized = value.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }
}
