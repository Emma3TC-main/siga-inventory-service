package utp.siga.inventory.infrastructure.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import utp.siga.inventory.application.exception.EvidenceDependencyException;

class HttpEvidenceAvailabilityAdapterTest {
    private MockRestServiceServer server;
    private HttpEvidenceAvailabilityAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://evidence.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new HttpEvidenceAvailabilityAdapter(builder.build());
    }

    @Test
    void forwardsBearerAndCanonicalScopedQueryAndFindsAvailableEvidence() {
        UUID movementId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(
                        "http://evidence.test/api/v1/evidences?")))
                .andExpect(queryParam("ownerType", "MOVEMENT"))
                .andExpect(queryParam("ownerId", movementId.toString()))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "1"))
                .andExpect(header("Authorization", "Bearer original-token"))
                .andExpect(header("X-Correlation-ID", correlationId.toString()))
                .andRespond(withSuccess(page(movementId, "AVAILABLE"), MediaType.APPLICATION_JSON));

        assertTrue(adapter.hasAvailableMovementEvidence(movementId, "Bearer original-token", correlationId));
        server.verify();
    }

    @Test
    void returnsFalseForEmptyPageOrOwnerNotFound() {
        UUID movementId = UUID.randomUUID();
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://evidence.test/api/v1/evidences?")))
                .andRespond(withSuccess(emptyPage(), MediaType.APPLICATION_JSON));
        assertFalse(adapter.hasAvailableMovementEvidence(movementId, "Bearer original-token", UUID.randomUUID()));
        server.verify();

        RestClient.Builder builder = RestClient.builder().baseUrl("http://evidence.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new HttpEvidenceAvailabilityAdapter(builder.build());
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://evidence.test/api/v1/evidences?")))
                .andRespond(withResourceNotFound());
        assertFalse(adapter.hasAvailableMovementEvidence(movementId, "Bearer original-token", UUID.randomUUID()));
        server.verify();
    }

    @Test
    void failsClosedForMalformedOrUnauthorizedResponse() {
        UUID movementId = UUID.randomUUID();
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://evidence.test/api/v1/evidences?")))
                .andRespond(withSuccess("{\"content\":[{\"status\":\"UNKNOWN\"}]}", MediaType.APPLICATION_JSON));
        assertThrows(EvidenceDependencyException.class, () -> adapter.hasAvailableMovementEvidence(
                movementId, "Bearer original-token", UUID.randomUUID()));
        server.verify();

        RestClient.Builder builder = RestClient.builder().baseUrl("http://evidence.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new HttpEvidenceAvailabilityAdapter(builder.build());
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://evidence.test/api/v1/evidences?")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThrows(EvidenceDependencyException.class, () -> adapter.hasAvailableMovementEvidence(
                movementId, "Bearer original-token", UUID.randomUUID()));
        server.verify();
    }

    private static String page(UUID movementId, String status) {
        return "{\"content\":[{\"ownerType\":\"MOVEMENT\",\"ownerId\":\"" + movementId
                + "\",\"status\":\"" + status
                + "\"}],\"page\":0,\"size\":1,\"totalElements\":1,\"totalPages\":1,\"first\":true,\"last\":true}";
    }

    private static String emptyPage() {
        return "{\"content\":[],\"page\":0,\"size\":1,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}";
    }
}
