package utp.siga.inventory.presentation.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.inventory.application.dto.InventoryPage;
import utp.siga.inventory.application.dto.MovementResponse;
import utp.siga.inventory.application.dto.StockItem;
import utp.siga.inventory.application.query.GetMovementQuery;
import utp.siga.inventory.application.query.GetStockQuery;
import utp.siga.inventory.application.port.in.MovementUseCase;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;
import utp.siga.inventory.infrastructure.security.JwtStepUpValidator;
import utp.siga.inventory.infrastructure.security.SecurityConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {StockController.class, MovementController.class},
        properties = {
            "INVENTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/siga_inventory_web_test",
            "INVENTORY_DB_USER=siga_inventory_web_test",
            "INVENTORY_DB_PASSWORD=not-used",
            "INVENTORY_RABBIT_USER=not-used",
            "INVENTORY_RABBIT_PASSWORD=not-used",
            "inventory.test.expected-database=siga_inventory_web_test",
            "inventory.test.expected-user=siga_inventory_web_test",
            "inventory.outbox.publisher.initial-delay-ms=86400000",
            "inventory.outbox.publisher.fixed-delay-ms=86400000"
        })
@Import(SecurityConfiguration.class)
class QueriesControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private GetStockQuery getStockQuery;

    @MockBean
    private GetMovementQuery getMovementQuery;

    @MockBean
    private MovementUseCase movementUseCase;

    @MockBean
    private JwtAuthenticatedActorAdapter actorAdapter;

    @MockBean
    private JwtStepUpValidator stepUpValidator;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void inventoryRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/inventory"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    @WithMockUser(authorities = "MOVEMENT_CREATE")
    void inventoryRejectsWrongPermission() throws Exception {
        mvc.perform(get("/api/v1/inventory"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void getInventoryPage() throws Exception {
        UUID productId = UUID.randomUUID();
        StockItem item = new StockItem(UUID.randomUUID(), productId, UUID.randomUUID(), null, null, BigDecimal.TEN, BigDecimal.ONE);
        InventoryPage page = new InventoryPage(List.of(item), 0, 20, 1);

        Mockito.when(getStockQuery.getStock(productId, null, null, null, 0, 20)).thenReturn(page);

        mvc.perform(get("/api/v1/inventory")
                .param("productId", productId.toString()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].productId").value(productId.toString()))
           .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void getMovementById() throws Exception {
        UUID id = UUID.randomUUID();
        MovementResponse response = new MovementResponse(id, "MOV-001", "ENTRY", "CONFIRMED", UUID.randomUUID(), null, null, null, null, null, null, false, null, List.of(), null, null, null);

        Mockito.when(getMovementQuery.getMovement(id)).thenReturn(Optional.of(response));

        mvc.perform(get("/api/v1/movements/{id}", id))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.movementCode").value("MOV-001"));
    }

    @Test
    @WithMockUser(authorities = "MOVEMENT_CREATE")
    void movementReadRejectsWrongPermission() throws Exception {
        mvc.perform(get("/api/v1/movements/{id}", UUID.randomUUID()))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void missingMovementUsesProblemDetails() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(getMovementQuery.getMovement(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/movements/{id}", id).header("X-Correlation-Id", UUID.randomUUID()))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value("MOVEMENT_NOT_FOUND"))
           .andExpect(jsonPath("$.instance").value("/api/v1/movements/" + id))
           .andExpect(jsonPath("$.correlationId").exists());
    }
}
