package utp.siga.inventory.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import utp.siga.inventory.application.exception.EvidenceDependencyException;
import utp.siga.inventory.application.port.out.EvidenceAvailabilityPort;

@Component
public class HttpEvidenceAvailabilityAdapter implements EvidenceAvailabilityPort {
    private static final Set<String> STATUSES = Set.of(
            "RECEIVED", "VALIDATING", "STORED", "AVAILABLE", "REJECTED", "ARCHIVED");
    private final RestClient client;

    public HttpEvidenceAvailabilityAdapter(@Qualifier("evidenceRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public boolean hasAvailableMovementEvidence(UUID movementId, String bearerToken, UUID correlationId) {
        if (movementId == null || bearerToken == null || !bearerToken.startsWith("Bearer ")
                || bearerToken.length() <= "Bearer ".length()) {
            throw new EvidenceDependencyException("Authenticated bearer is required for Evidence");
        }
        try {
            return client.get()
                    .uri(builder -> builder.path("/api/v1/evidences")
                            .queryParam("ownerType", "MOVEMENT")
                            .queryParam("ownerId", movementId)
                            .queryParam("page", 0)
                            .queryParam("size", 1)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .header("X-Correlation-ID", correlationId.toString())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return false;
                        }
                        if (status != 200) {
                            throw new EvidenceDependencyException("Evidence returned HTTP " + status);
                        }
                        return containsAvailable(response.bodyTo(JsonNode.class), movementId);
                    });
        } catch (EvidenceDependencyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EvidenceDependencyException("Evidence availability check failed", exception);
        }
    }

    private boolean containsAvailable(JsonNode body, UUID movementId) {
        if (body == null || !body.isObject() || !body.path("content").isArray()
                || !body.path("page").canConvertToInt() || !body.path("size").canConvertToInt()
                || !body.path("totalElements").canConvertToLong()
                || !body.path("totalPages").canConvertToInt()
                || !body.path("first").isBoolean() || !body.path("last").isBoolean()) {
            throw new EvidenceDependencyException("Evidence returned a malformed page");
        }
        for (JsonNode item : body.path("content")) {
            JsonNode status = item.path("status");
            JsonNode ownerType = item.path("ownerType");
            JsonNode ownerId = item.path("ownerId");
            if (!item.isObject() || !status.isTextual() || !STATUSES.contains(status.textValue())
                    || !ownerType.isTextual() || !"MOVEMENT".equals(ownerType.textValue())
                    || !ownerId.isTextual() || !movementId.toString().equals(ownerId.textValue())) {
                throw new EvidenceDependencyException("Evidence returned a malformed item");
            }
            if ("AVAILABLE".equals(status.textValue())) {
                return true;
            }
        }
        return false;
    }
}
