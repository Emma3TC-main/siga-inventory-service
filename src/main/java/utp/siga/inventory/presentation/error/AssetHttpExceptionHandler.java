package utp.siga.inventory.presentation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import utp.siga.inventory.application.exception.AssetConflictException;
import utp.siga.inventory.application.exception.AssetNotFoundException;
import utp.siga.inventory.presentation.rest.AssetAdminController;

@RestControllerAdvice(assignableTypes = AssetAdminController.class)
public class AssetHttpExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> validation(Exception exception, HttpServletRequest request) {
        return problem(request, 400, "VALIDATION_ERROR", "Invalid asset request");
    }

    @ExceptionHandler(AssetNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(AssetNotFoundException exception, HttpServletRequest request) {
        return problem(request, 404, "ASSET_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AssetConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(AssetConflictException exception, HttpServletRequest request) {
        return problem(request, 409, exception.code(), exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> problem(HttpServletRequest request, int status,
            String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.body(request, status, code, detail));
    }
}
