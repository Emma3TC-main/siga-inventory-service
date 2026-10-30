package utp.siga.inventory.presentation.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.inventory.application.dto.AssetAdminPage;
import utp.siga.inventory.application.dto.AssetAdminView;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.port.in.AssetAdminUseCase;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;
import utp.siga.inventory.infrastructure.security.SecurityConfiguration;

@WebMvcTest(controllers = AssetAdminController.class, properties = {
        "INVENTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/siga_inventory_asset_web_test",
        "INVENTORY_DB_USER=siga_inventory_asset_web_test", "INVENTORY_DB_PASSWORD=not-used",
        "INVENTORY_RABBIT_USER=not-used", "INVENTORY_RABBIT_PASSWORD=not-used",
        "inventory.test.expected-database=siga_inventory_asset_web_test",
        "inventory.test.expected-user=siga_inventory_asset_web_test" })
@Import(SecurityConfiguration.class)
class AssetAdminControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean AssetAdminUseCase assets;
    @MockBean JwtAuthenticatedActorAdapter actors;
    @MockBean JwtDecoder jwtDecoder;

    @Test void unauthenticatedAdminListIs401() throws Exception {
        mvc.perform(get("/api/v1/admin/assets")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test void unrelatedPermissionIs403() throws Exception {
        mvc.perform(get("/api/v1/admin/assets").with(jwt().authorities(createAuthorityList("MOVEMENT_CREATE"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test void inventoryReadAndTraceabilityManageCanRead() throws Exception {
        when(assets.list(any())).thenReturn(new AssetAdminPage(List.of(), 0, 20, 0));
        mvc.perform(get("/api/v1/admin/assets").with(jwt().authorities(createAuthorityList("INVENTORY_READ"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/assets").with(jwt().authorities(createAuthorityList("TRACEABILITY_MANAGE"))))
                .andExpect(status().isOk());
    }

    @Test void inventoryReadCannotMutate() throws Exception {
        mvc.perform(post("/api/v1/assets").with(jwt().authorities(createAuthorityList("INVENTORY_READ")))
                .contentType(APPLICATION_JSON).content("{\"productId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void traceabilityManageRegistersAndDerivesActorFromJwt() throws Exception {
        AssetAdminView result = view();
        when(actors.actorUserId(any())).thenReturn(UUID.randomUUID());
        when(assets.register(any())).thenReturn(result);
        mvc.perform(post("/api/v1/assets").with(jwt().authorities(createAuthorityList("TRACEABILITY_MANAGE")))
                .contentType(APPLICATION_JSON).content("{\"productId\":\"" + result.productId()
                        + "\",\"assetCode\":\"A-1\",\"serialNumber\":\"S-1\","
                        + "\"technicalSnapshot\":{}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0));
    }

    @Test void updateRequiresCanonicalFieldsAndRejectsImmutableOnes() throws Exception {
        mvc.perform(put("/api/v1/assets/" + UUID.randomUUID())
                .with(jwt().authorities(createAuthorityList("TRACEABILITY_MANAGE")))
                .contentType(APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(put("/api/v1/assets/" + UUID.randomUUID())
                .with(jwt().authorities(createAuthorityList("TRACEABILITY_MANAGE")))
                .contentType(APPLICATION_JSON).content("{\"brand\":null,\"model\":null,"
                        + "\"technicalSnapshot\":{},\"expectedVersion\":0,\"assetCode\":\"changed\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void statusContractRejectsEnTransito() throws Exception {
        when(actors.actorUserId(any())).thenReturn(UUID.randomUUID());
        when(assets.changeStatus(any())).thenThrow(new IllegalArgumentException("reserved status"));
        mvc.perform(post("/api/v1/assets/" + UUID.randomUUID() + "/status")
                .with(jwt().authorities(createAuthorityList("TRACEABILITY_MANAGE")))
                .contentType(APPLICATION_JSON).content("{\"targetStatus\":\"EN_TRANSITO\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private AssetAdminView view() {
        Instant now = Instant.parse("2026-10-28T12:00:00Z");
        return new AssetAdminView(UUID.randomUUID(), UUID.randomUUID(), "A-1", "S-1", null, null,
                AssetOperationalStatus.OPERATIVO, mapper.createObjectNode(), null, null, false, 0, now, now);
    }
}
