package utp.siga.inventory.presentation.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class Problems {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        MAPPER.writeValue(response.getOutputStream(), body(request, status, code,
                status == 401 ? "Solicitud no autorizada" : "Permiso insuficiente"));
    }

    public static Map<String, Object> body(
            HttpServletRequest request, int status, String code, String detail) {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("type", "about:blank");
        problem.put("title", getTitle(status));
        problem.put("status", status);
        problem.put("code", code);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        String correlationId = correlationId(request);
        if (correlationId != null && !correlationId.isBlank()) problem.put("correlationId", correlationId);
        return problem;
    }

    static String correlationId(HttpServletRequest request) {
        return request.getHeader("X-Correlation-Id");
    }

    private static String getTitle(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            default -> "Error";
        };
    }
}
