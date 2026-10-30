package utp.siga.inventory.presentation.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;
import utp.siga.inventory.application.command.AuthorizeMovementCommand;
import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.RejectMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.exception.EvidenceDependencyException;
import utp.siga.inventory.application.exception.EvidenceRequiredException;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.dto.MovementResponse;
import utp.siga.inventory.application.port.in.MovementUseCase;
import utp.siga.inventory.application.query.GetMovementQuery;
import utp.siga.inventory.domain.exception.InvalidMovementStateException;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementDetail;
import utp.siga.inventory.domain.model.MovementType;
import utp.siga.inventory.infrastructure.security.JwtAuthenticatedActorAdapter;
import utp.siga.inventory.infrastructure.security.JwtStepUpValidator;
import utp.siga.inventory.infrastructure.security.SecurityConfiguration;

@WebMvcTest(
        controllers = MovementController.class,
        properties = {
            "INVENTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/siga_inventory_web_test",
            "INVENTORY_DB_USER=siga_inventory_web_test",
            "INVENTORY_DB_PASSWORD=not-used",
            "INVENTORY_RABBIT_USER=not-used",
            "INVENTORY_RABBIT_PASSWORD=not-used",
            "inventory.test.expected-database=siga_inventory_web_test",
            "inventory.test.expected-user=siga_inventory_web_test",
            "inventory.security.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json",
            "inventory.security.issuer=siga-identity",
            "inventory.security.audience=siga-api"
        })
@Import({SecurityConfiguration.class, JwtAuthenticatedActorAdapter.class, JwtStepUpValidator.class})
class MovementHttpControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GetMovementQuery query;
    @MockBean MovementUseCase movements;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void listsAnEmptyPaginatedHistoryAndRequiresAuthentication() throws Exception {
        when(query.getMovements(2, 5)).thenReturn(new MovementPage(List.of(), 2, 5, 0));

        mvc.perform(get("/api/v1/movements").param("page", "2").param("size", "5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        mvc.perform(get("/api/v1/movements").param("page", "2").param("size", "5")
                        .with(jwtWith(UUID.randomUUID(), "INVENTORY_READ", false, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(query).getMovements(2, 5);
    }

    @Test
    void movementListRejectsInvalidPaginationAsProblemDetails() throws Exception {
        when(query.getMovements(-1, 20)).thenThrow(new IllegalArgumentException("invalid page"));
        mvc.perform(get("/api/v1/movements").param("page", "-1")
                        .with(jwtWith(UUID.randomUUID(), "INVENTORY_READ", false, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createUsesJwtSubjectAndDoesNotExposeActorOrSensitivityInputs() throws Exception {
        UUID actor = UUID.randomUUID();
        Movement result = movement(MovementType.ENTRY, actor);
        when(movements.createMovement(any(), any())).thenReturn(result);

        mvc.perform(post("/api/v1/movements")
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryRequest(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registeredByUserId").value(actor.toString()))
                .andExpect(jsonPath("$.sensitive").value(false));

        ArgumentCaptor<CreateMovementCommand> command = ArgumentCaptor.forClass(CreateMovementCommand.class);
        ArgumentCaptor<UUID> capturedActor = ArgumentCaptor.forClass(UUID.class);
        verify(movements).createMovement(command.capture(), capturedActor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(actor, capturedActor.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(MovementType.ENTRY, command.getValue().movementType());
    }

    @Test
    void createRejectsActorSpoofingFieldInsteadOfIgnoringIt() throws Exception {
        UUID actor = UUID.randomUUID();
        mvc.perform(post("/api/v1/movements")
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryRequest("\"registeredByUserId\":\"" + UUID.randomUUID() + "\",")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(movements);
    }

    @Test
    void adjustmentRequiresInventoryAdjustRatherThanMovementCreate() throws Exception {
        UUID actor = UUID.randomUUID();
        String body = adjustmentRequest();
        mvc.perform(post("/api/v1/movements")
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        verifyNoInteractions(movements);
    }

    @Test
    void inventoryAdjustPermissionCanCreateAnAdjustment() throws Exception {
        UUID actor = UUID.randomUUID();
        when(movements.createMovement(any(), any())).thenReturn(movement(MovementType.ADJUST_POSITIVE, actor));
        mvc.perform(post("/api/v1/movements")
                        .with(jwtWith(actor, "INVENTORY_ADJUST", false, 0))
                        .contentType(MediaType.APPLICATION_JSON).content(adjustmentRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("ADJUST_POSITIVE"));
    }

    @Test
    void submitUsesExistingApplicationTransitionWithoutClientSensitivity() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID(), correlation = UUID.randomUUID();
        when(query.getMovement(id)).thenReturn(Optional.of(response(id, "ENTRY")));
        when(movements.submitMovement(any(), anyBoolean())).thenReturn(movement(MovementType.ENTRY, actor));

        mvc.perform(post("/api/v1/movements/{id}/submit", id)
                        .header("X-Correlation-ID", correlation)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                .andExpect(status().isOk());
        ArgumentCaptor<SubmitMovementCommand> command = ArgumentCaptor.forClass(SubmitMovementCommand.class);
        verify(movements).submitMovement(command.capture(), org.mockito.ArgumentMatchers.eq(false));
        org.junit.jupiter.api.Assertions.assertEquals(id, command.getValue().movementId());
        org.junit.jupiter.api.Assertions.assertEquals(correlation, command.getValue().correlationId());
        assertThat(command.getValue().bearerToken()).startsWith("Bearer ");
    }

    @Test
    void submitMapsMissingEvidenceAndDependencyFailureToCanonicalProblems() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        when(query.getMovement(id)).thenReturn(Optional.of(response(id, "ADJUST_POSITIVE")));
        when(movements.submitMovement(any(), anyBoolean()))
                .thenThrow(new EvidenceRequiredException())
                .thenThrow(new EvidenceDependencyException("timeout"));

        mvc.perform(post("/api/v1/movements/{id}/submit", id)
                        .with(jwtWith(actor, "INVENTORY_ADJUST", false, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("El ajuste requiere al menos una evidencia disponible."));

        mvc.perform(post("/api/v1/movements/{id}/submit", id)
                        .with(jwtWith(actor, "INVENTORY_ADJUST", false, 0)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EVIDENCE_SERVICE_UNAVAILABLE"));
    }

    @Test
    void authorizeUsesJwtActorAndValidRecentOtpStepUp() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        mvc.perform(post("/api/v1/movements/{id}/authorize", id)
                        .with(jwtWith(actor, "MOVEMENT_AUTHORIZE", true, Instant.now().minusSeconds(30).getEpochSecond()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"approved\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuthorizeMovementCommand> command = ArgumentCaptor.forClass(AuthorizeMovementCommand.class);
        ArgumentCaptor<UUID> capturedActor = ArgumentCaptor.forClass(UUID.class);
        verify(movements).authorizeMovement(command.capture(), capturedActor.capture(), org.mockito.ArgumentMatchers.eq(true));
        org.junit.jupiter.api.Assertions.assertEquals(id, command.getValue().movementId());
        org.junit.jupiter.api.Assertions.assertEquals(actor, capturedActor.getValue());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void createLogsConcreteValidationCauseWithCorrelationAndMovementType(CapturedOutput output) throws Exception {
        UUID actor = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String cause = "No active direct conversion to base unit for product fixture and unit PZA";
        when(movements.createMovement(any(), any())).thenThrow(new IllegalArgumentException(cause));

        mvc.perform(post("/api/v1/movements")
                        .header("X-Correlation-ID", correlationId)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryRequest(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("La solicitud de movimiento no es válida"))
                .andExpect(jsonPath("$.correlationId").value(correlationId.toString()));

        assertThat(output).contains("correlationId=" + correlationId)
                .contains("movementType=ENTRY")
                .contains("cause=" + cause);
    }

    @Test
    void authorizeRejectsMissingOtpAndStaleAuthentication() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        mvc.perform(post("/api/v1/movements/{id}/authorize", id)
                        .with(jwtWith(actor, "MOVEMENT_AUTHORIZE", false, Instant.now().getEpochSecond()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/movements/{id}/authorize", id)
                        .with(jwtWith(actor, "MOVEMENT_AUTHORIZE", true, Instant.now().minusSeconds(600).getEpochSecond()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(movements, never()).authorizeMovement(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void operatorCannotAuthorizeOrReject() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();

        mvc.perform(post("/api/v1/movements/{id}/authorize", id)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", true, Instant.now().minusSeconds(30).getEpochSecond()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"approved\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mvc.perform(post("/api/v1/movements/{id}/reject", id)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", true, Instant.now().minusSeconds(30).getEpochSecond()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"denied\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verifyNoInteractions(movements);
    }

    @Test
    void rejectMapsInvalidTransitionToConflict() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        when(movements.rejectMovement(any(), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenThrow(new InvalidMovementStateException("invalid"));
        mvc.perform(post("/api/v1/movements/{id}/reject", id)
                        .with(jwtWith(actor, "MOVEMENT_AUTHORIZE", true, Instant.now().minusSeconds(30).getEpochSecond()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"denied\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVEMENT_STATE_CONFLICT"));
        verify(movements).rejectMovement(any(RejectMovementCommand.class), any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void cancelUsesCanonicalPermissionAndMapsStateConflict() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        when(query.getMovement(id)).thenReturn(Optional.of(response(id, "ENTRY")));
        when(movements.cancelMovement(any())).thenThrow(new InvalidMovementStateException("invalid"));
        mvc.perform(post("/api/v1/movements/{id}/cancel", id)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVEMENT_STATE_CONFLICT"));
    }

    @Test
    void confirmRequiresIdempotencyKeyAndPassesStableKeyActorAndCorrelation() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID(), key = UUID.randomUUID(), correlation = UUID.randomUUID();
        Movement result = movement(MovementType.ENTRY, actor);
        when(query.getMovement(id)).thenReturn(Optional.of(response(id, "ENTRY")));
        when(movements.confirmMovement(any())).thenReturn(result);

        mvc.perform(post("/api/v1/movements/{id}/confirm", id)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/movements/{id}/confirm", id)
                        .header("Idempotency-Key", key).header("X-Correlation-ID", correlation)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                .andExpect(status().isOk());

        ArgumentCaptor<ConfirmMovementCommand> command = ArgumentCaptor.forClass(ConfirmMovementCommand.class);
        verify(movements).confirmMovement(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(key, command.getValue().idempotencyKey());
        org.junit.jupiter.api.Assertions.assertEquals(actor, command.getValue().actorUserId());
        org.junit.jupiter.api.Assertions.assertEquals(correlation, command.getValue().correlationId());
        org.junit.jupiter.api.Assertions.assertEquals(false, command.getValue().actorCanAuthorize());
    }

    @Test
    void confirmReplayReturnsPriorResultAndDifferentRequestConflictIsProblemDetails() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID(), key = UUID.randomUUID();
        when(query.getMovement(id)).thenReturn(Optional.of(response(id, "ENTRY")));
        when(movements.confirmMovement(any()))
                .thenReturn(movement(MovementType.ENTRY, actor))
                .thenReturn(movement(MovementType.ENTRY, actor))
                .thenThrow(new IllegalStateException("Idempotency conflict"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/movements/{id}/confirm", id).header("Idempotency-Key", key)
                            .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/movements/{id}/confirm", id).header("Idempotency-Key", key)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVEMENT_CONFLICT"));
    }

    @Test
    void commandForMissingMovementReturns404AndWrongPermissionReturns403() throws Exception {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        when(query.getMovement(id)).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/movements/{id}/submit", id)
                        .with(jwtWith(actor, "MOVEMENT_CREATE", false, 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVEMENT_NOT_FOUND"));
        mvc.perform(post("/api/v1/movements/{id}/submit", id)
                        .with(jwtWith(actor, "INVENTORY_READ", false, 0)))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtWith(
            UUID actor, String authority, boolean otp, long authTime) {
        return jwt().jwt(builder -> {
            builder.subject(actor.toString()).claim("permissions", List.of(authority));
            if (otp) builder.claim("amr", List.of("pwd", "otp"));
            else builder.claim("amr", List.of("pwd"));
            if (authTime > 0) builder.claim("auth_time", authTime);
        }).authorities(new SimpleGrantedAuthority(authority));
    }

    private static String entryRequest(String extraField) {
        return "{" + (extraField == null ? "" : extraField)
                + "\"movementType\":\"ENTRY\",\"details\":[{\"productId\":\"" + UUID.randomUUID()
                + "\",\"enteredQuantity\":1,\"unitCode\":\"EA\",\"destinationLocationId\":\""
                + UUID.randomUUID() + "\"}]}";
    }

    private static String adjustmentRequest() {
        return "{\"movementType\":\"ADJUST_POSITIVE\",\"reason\":\"count\",\"details\":[{\"productId\":\""
                + UUID.randomUUID() + "\",\"enteredQuantity\":1,\"unitCode\":\"EA\",\"destinationLocationId\":\""
                + UUID.randomUUID() + "\"}]}";
    }

    private static Movement movement(MovementType type, UUID actor) {
        UUID origin = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        MovementDetail detail = new MovementDetail(null, 1, UUID.randomUUID(), BigDecimal.ONE, "EA",
                BigDecimal.ONE,
                type == MovementType.ENTRY || type == MovementType.ADJUST_POSITIVE ? null : origin,
                type == MovementType.EXIT || type == MovementType.ADJUST_NEGATIVE ? null : destination,
                null, null, "PEN", BigDecimal.ONE, null, null, null);
        Movement.Builder builder = new Movement.Builder().movementCode("MOV-2026-00001").type(type)
                .registeredByUserId(actor).addDetail(detail);
        if (type == MovementType.ADJUST_POSITIVE || type == MovementType.ADJUST_NEGATIVE) builder.reason("count");
        return builder.build();
    }

    private static MovementResponse response(UUID id, String type) {
        return new MovementResponse(id, "MOV-2026-00001", type, "DRAFT", UUID.randomUUID(), null,
                null, null, null, null, null, false, null, List.of(), Instant.now(), null, null);
    }
}
