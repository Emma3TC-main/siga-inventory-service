package utp.siga.inventory.presentation.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.dto.AssetPage;
import utp.siga.inventory.application.dto.AssetView;
import utp.siga.inventory.application.dto.LotPage;
import utp.siga.inventory.application.dto.LotStatus;
import utp.siga.inventory.application.dto.LotView;
import utp.siga.inventory.application.port.out.AssetQueryPort;
import utp.siga.inventory.application.port.out.LotQueryPort;
import utp.siga.inventory.application.usecase.DefaultListAssetsQuery;
import utp.siga.inventory.application.usecase.DefaultListLotsQuery;
import utp.siga.inventory.infrastructure.security.SecurityConfiguration;
import utp.siga.inventory.presentation.error.TraceabilityHttpExceptionHandler;

@WebMvcTest(
        controllers = {LotController.class, AssetController.class},
        properties = {
            "INVENTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/siga_inventory_traceability_web_test",
            "INVENTORY_DB_USER=siga_inventory_traceability_web_test",
            "INVENTORY_DB_PASSWORD=not-used",
            "INVENTORY_RABBIT_USER=not-used",
            "INVENTORY_RABBIT_PASSWORD=not-used",
            "inventory.test.expected-database=siga_inventory_traceability_web_test",
            "inventory.test.expected-user=siga_inventory_traceability_web_test"
        })
@Import({SecurityConfiguration.class, DefaultListLotsQuery.class, DefaultListAssetsQuery.class,
        TraceabilityHttpExceptionHandler.class})
class TraceabilityControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("22000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockBean
    LotQueryPort lots;

    @MockBean
    AssetQueryPort assets;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void lotsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/lots").param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    @WithMockUser(authorities = "MOVEMENT_CREATE")
    void lotsRequireInventoryRead() throws Exception {
        mvc.perform(get("/api/v1/lots").param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void assetsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/assets")
                        .param("productId", PRODUCT_ID.toString())
                        .param("availability", "UNASSIGNED"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    @WithMockUser(authorities = "MOVEMENT_CREATE")
    void assetsRequireInventoryRead() throws Exception {
        mvc.perform(get("/api/v1/assets")
                        .param("productId", PRODUCT_ID.toString())
                        .param("availability", "UNASSIGNED"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void lotsRejectMissingProductAndInvalidPagination() throws Exception {
        mvc.perform(get("/api/v1/lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/lots")
                        .param("productId", PRODUCT_ID.toString()).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void assetsRejectMissingOrUnknownAvailability() throws Exception {
        mvc.perform(get("/api/v1/assets").param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/assets")
                        .param("productId", PRODUCT_ID.toString()).param("availability", "AVAILABLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void returnsCanonicalLotPage() throws Exception {
        UUID lotId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        when(lots.findOperationalLots(eq(PRODUCT_ID), eq(locationId), eq(true),
                any(LocalDate.class), eq(0), eq(20))).thenReturn(new LotPage(List.of(
                        new LotView(lotId, PRODUCT_ID, "LOT-01", "HEAT-01",
                                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"),
                                LocalDate.parse("2027-01-01"), LotStatus.ACTIVE, locationId,
                                new BigDecimal("3.500000"))), 0, 20, 1));

        mvc.perform(get("/api/v1/lots")
                        .param("productId", PRODUCT_ID.toString())
                        .param("locationId", locationId.toString())
                        .param("availableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(lotId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].locationId").value(locationId.toString()))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(3.5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(authorities = "INVENTORY_READ")
    void returnsCanonicalAssetPage() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        when(assets.findOperationalAssets(PRODUCT_ID, locationId, lotId,
                AssetAvailability.IN_STOCK, 1, 10)).thenReturn(new AssetPage(List.of(
                        new AssetView(assetId, PRODUCT_ID, "ASSET-01", "SERIAL-01",
                                AssetOperationalStatus.OPERATIVO, "Brand", "Model",
                                locationId, lotId, true)), 1, 10, 11));

        mvc.perform(get("/api/v1/assets")
                        .param("productId", PRODUCT_ID.toString())
                        .param("locationId", locationId.toString())
                        .param("lotId", lotId.toString())
                        .param("availability", "IN_STOCK")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(assetId.toString()))
                .andExpect(jsonPath("$.content[0].operationalStatus").value("OPERATIVO"))
                .andExpect(jsonPath("$.content[0].available").value(true))
                .andExpect(jsonPath("$.content[0].locationId").value(locationId.toString()))
                .andExpect(jsonPath("$.content[0].lotId").value(lotId.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(11));
    }
}
