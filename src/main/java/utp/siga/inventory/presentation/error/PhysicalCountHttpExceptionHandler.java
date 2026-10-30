package utp.siga.inventory.presentation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import utp.siga.inventory.application.exception.PhysicalCountConflictException;
import utp.siga.inventory.application.exception.PhysicalCountNotFoundException;
import utp.siga.inventory.application.exception.PhysicalCountValidationException;
import utp.siga.inventory.presentation.rest.PhysicalCountController;

@RestControllerAdvice(assignableTypes = PhysicalCountController.class)
public class PhysicalCountHttpExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> malformed(Exception exception, HttpServletRequest request) {
        return problem(request, 400, "VALIDATION_ERROR", "La solicitud de conteo físico no es válida");
    }

    @ExceptionHandler(PhysicalCountValidationException.class)
    ResponseEntity<Map<String, Object>> validation(PhysicalCountValidationException exception,
            HttpServletRequest request) {
        return problem(request, 400, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(PhysicalCountNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(PhysicalCountNotFoundException exception,
            HttpServletRequest request) {
        return problem(request, 404, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(PhysicalCountConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(PhysicalCountConflictException exception,
            HttpServletRequest request) {
        return problem(request, 409, exception.code(), exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> problem(HttpServletRequest request, int status,
            String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.body(request, status, code, detail));
    }
}
