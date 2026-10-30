package utp.siga.inventory.presentation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import utp.siga.inventory.application.exception.LocationConflictException;
import utp.siga.inventory.application.exception.LocationNotFoundException;
import utp.siga.inventory.presentation.rest.LocationController;

@RestControllerAdvice(assignableTypes = LocationController.class)
public class LocationHttpExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception exception, HttpServletRequest request) {
        return problem(request, 400, "VALIDATION_ERROR", "Invalid location request");
    }

    @ExceptionHandler(LocationNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(LocationNotFoundException exception, HttpServletRequest request) {
        return problem(request, 404, "LOCATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(LocationConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(LocationConflictException exception, HttpServletRequest request) {
        return problem(request, 409, exception.code(), exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> problem(HttpServletRequest request, int status,
            String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.body(request, status, code, detail));
    }
}
