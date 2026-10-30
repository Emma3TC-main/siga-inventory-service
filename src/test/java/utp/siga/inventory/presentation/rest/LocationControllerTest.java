package utp.siga.inventory.presentation.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.inventory.application.dto.LocationType;
import utp.siga.inventory.application.dto.LocationView;
import utp.siga.inventory.application.dto.LocationActiveFilter;
import utp.siga.inventory.application.port.in.LocationUseCase;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;
import utp.siga.inventory.infrastructure.security.SecurityConfiguration;

@WebMvcTest(
        controllers = LocationController.class,
        properties = {
            "INVENTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/siga_inventory_location_web_test",
            "INVENTORY_DB_USER=siga_inventory_location_web_test",
            "INVENTORY_DB_PASSWORD=not-used",
            "INVENTORY_RABBIT_USER=not-used",
            "INVENTORY_RABBIT_PASSWORD=not-used",
            "inventory.test.expected-database=siga_inventory_location_web_test",
            "inventory.test.expected-user=siga_inventory_location_web_test"
        })
@Import(SecurityConfiguration.class)
class LocationControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    LocationUseCase locations;

    @MockBean
    JwtAuthenticatedActorAdapter actors;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/locations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    @WithMockUser(authorities = "MOVEMENT_CREATE")
    void rejectsUserWithoutInventoryRead() throws Exception {
        mvc.perform(get("/api/v1/locations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void returnsFlatLocationResponseSchema() throws Exception {
        UUID id = UUID.randomUUID();
        when(locations.list(LocationActiveFilter.TRUE)).thenReturn(List.of(
                new LocationView(id, "ALM-01", "Almacén principal", LocationType.ALMACEN,
                        null, true, 3)));

        mvc.perform(get("/api/v1/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].code").value("ALM-01"))
                .andExpect(jsonPath("$[0].name").value("Almacén principal"))
                .andExpect(jsonPath("$[0].locationType").value("ALMACEN"))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].version").value(3));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void returnsEmptyCollection() throws Exception {
        when(locations.list(LocationActiveFilter.TRUE)).thenReturn(List.of());

        mvc.perform(get("/api/v1/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void locationManageCanReadWithoutInventoryRead() throws Exception {
        when(locations.list(LocationActiveFilter.TRUE)).thenReturn(List.of());
        mvc.perform(get("/api/v1/locations").with(jwt().authorities(createAuthorityList("LOCATION_MANAGE"))))
                .andExpect(status().isOk());
    }

    @Test
    void inventoryReadCannotCreateLocation() throws Exception {
        mvc.perform(post("/api/v1/locations").with(jwt().authorities(createAuthorityList("INVENTORY_READ")))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"NEW\",\"name\":\"New\",\"locationType\":\"ALMACEN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void locationManageCreatesLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(actors.actorUserId(any())).thenReturn(UUID.randomUUID());
        when(locations.create(any())).thenReturn(new LocationView(id, "NEW", "New", LocationType.ALMACEN,
                null, true, 0));
        mvc.perform(post("/api/v1/locations").with(jwt().authorities(createAuthorityList("LOCATION_MANAGE")))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"NEW\",\"name\":\"New\",\"locationType\":\"ALMACEN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }
}
