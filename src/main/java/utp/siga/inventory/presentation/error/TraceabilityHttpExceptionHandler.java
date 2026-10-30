package utp.siga.inventory.presentation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import utp.siga.inventory.presentation.rest.AssetController;
import utp.siga.inventory.presentation.rest.LotController;

@RestControllerAdvice(assignableTypes = {LotController.class, AssetController.class})
public class TraceabilityHttpExceptionHandler {

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> validation(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.body(request, 400, "VALIDATION_ERROR",
                        "La consulta de trazabilidad no es válida"));
    }
}
