package utp.siga.inventory.presentation.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.inventory.application.dto.PhysicalCountPage;
import utp.siga.inventory.application.exception.PhysicalCountConflictException;
import utp.siga.inventory.application.port.in.PhysicalCountUseCase;
import utp.siga.inventory.domain.model.PhysicalCount;
import utp.siga.inventory.domain.model.PhysicalCountStatus;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;
import utp.siga.inventory.infrastructure.security.SecurityConfiguration;
import utp.siga.inventory.presentation.error.PhysicalCountHttpExceptionHandler;

@WebMvcTest(controllers = PhysicalCountController.class, properties = {
        "INVENTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/siga_inventory_count_web_test",
        "INVENTORY_DB_USER=siga_inventory_count_web_test",
        "INVENTORY_DB_PASSWORD=not-used",
        "INVENTORY_RABBIT_USER=not-used",
        "INVENTORY_RABBIT_PASSWORD=not-used",
        "inventory.test.expected-database=siga_inventory_count_web_test",
        "inventory.test.expected-user=siga_inventory_count_web_test",
        "inventory.security.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json",
        "inventory.security.issuer=siga-identity",
        "inventory.security.audience=siga-api"
})
@Import({SecurityConfiguration.class, JwtAuthenticatedActorAdapter.class,
        PhysicalCountHttpExceptionHandler.class})
class PhysicalCountControllerTest {
    @Autowired MockMvc mvc;
    @MockBean PhysicalCountUseCase counts;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void everyEndpointRequiresAuthenticationAndInventoryCountPermission() throws Exception {
        mvc.perform(get("/api/v1/counts")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        mvc.perform(get("/api/v1/counts").with(jwtWith(UUID.randomUUID(), "INVENTORY_READ")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        verifyNoInteractions(counts);
    }

    @Test
    void listUsesCanonicalPaginationWithPermission() throws Exception {
        when(counts.list(2, 5)).thenReturn(new PhysicalCountPage(List.of(), 2, 5, 0));
        mvc.perform(get("/api/v1/counts").param("page", "2").param("size", "5")
                        .with(jwtWith(UUID.randomUUID(), "INVENTORY_COUNT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void createDerivesActorFromJwtAndRequiresIdempotencyKey() throws Exception {
        UUID actor = UUID.randomUUID(), location = UUID.randomUUID(), key = UUID.randomUUID();
        when(counts.create(any())).thenReturn(count(actor, location));
        String body = "{\"locationId\":\"" + location + "\",\"notes\":\"cycle\"}";

        mvc.perform(post("/api/v1/counts").with(jwtWith(actor, "INVENTORY_COUNT"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/counts").with(jwtWith(actor, "INVENTORY_COUNT"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.createdByUserId").value(actor.toString()));

        ArgumentCaptor<PhysicalCountUseCase.Create> command = ArgumentCaptor.forClass(PhysicalCountUseCase.Create.class);
        verify(counts).create(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(actor, command.getValue().actorUserId());
        org.junit.jupiter.api.Assertions.assertEquals(key, command.getValue().idempotencyKey());
    }

    @Test
    void versionConflictMapsToCanonical409AndClientActorSpoofingIsRejected() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        when(counts.close(any())).thenThrow(new PhysicalCountConflictException(
                "VERSION_CONFLICT", "Physical count version is stale"));
        mvc.perform(post("/api/v1/counts/{id}/close", id).with(jwtWith(actor, "INVENTORY_COUNT"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mvc.perform(post("/api/v1/counts").with(jwtWith(actor, "INVENTORY_COUNT"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + UUID.randomUUID()
                                + "\",\"createdByUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtWith(
            UUID actor, String permission) {
        return jwt().jwt(builder -> builder.subject(actor.toString()).claim("permissions", List.of(permission)))
                .authorities(new SimpleGrantedAuthority(permission));
    }

    private static PhysicalCount count(UUID actor, UUID location) {
        Instant now = Instant.now();
        return new PhysicalCount(UUID.randomUUID(), location, PhysicalCountStatus.DRAFT, 0,
                "cycle", actor, null, null, null, null, null, null, now, now, List.of());
    }
}
