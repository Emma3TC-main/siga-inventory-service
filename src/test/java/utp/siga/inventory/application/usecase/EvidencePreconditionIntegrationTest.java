package utp.siga.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import utp.siga.inventory.InventoryIntegrationEnvironment;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.exception.EvidenceDependencyException;
import utp.siga.inventory.application.exception.EvidenceRequiredException;
import utp.siga.inventory.application.port.in.MovementUseCase;
import utp.siga.inventory.application.port.out.EvidenceAvailabilityPort;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementStatus;
import utp.siga.inventory.domain.model.MovementType;

@SpringBootTest
class EvidencePreconditionIntegrationTest extends InventoryIntegrationEnvironment {
    @Autowired MovementUseCase movements;
    @Autowired JdbcTemplate jdbc;
    @MockBean EvidenceAvailabilityPort evidenceAvailability;

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ADJUST_POSITIVE", "ADJUST_NEGATIVE"})
    void availableEvidenceAllowsAdjustmentSubmit(MovementType type) {
        Movement draft = adjustment(type);
        UUID correlationId = UUID.randomUUID();
        when(evidenceAvailability.hasAvailableMovementEvidence(
                draft.getId(), "Bearer user-token", correlationId)).thenReturn(true);

        Movement submitted = movements.submitMovement(
                new SubmitMovementCommand(draft.getId(), "Bearer user-token", correlationId), false);

        assertEquals(MovementStatus.PENDING_AUTHORIZATION, submitted.getStatus());
        assertEquals("PENDING_AUTHORIZATION", status(draft.getId()));
    }

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ADJUST_POSITIVE", "ADJUST_NEGATIVE"})
    void missingEvidenceLeavesAdjustmentAndTransactionalStateUntouched(MovementType type) {
        Movement draft = adjustment(type);
        when(evidenceAvailability.hasAvailableMovementEvidence(any(), any(), any())).thenReturn(false);

        assertThrows(EvidenceRequiredException.class, () -> movements.submitMovement(
                new SubmitMovementCommand(draft.getId(), "Bearer user-token", UUID.randomUUID()), false));

        assertNoSubmitEffects(draft.getId());
    }

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ADJUST_POSITIVE", "ADJUST_NEGATIVE"})
    void dependencyFailureLeavesAdjustmentAndTransactionalStateUntouched(MovementType type) {
        Movement draft = adjustment(type);
        when(evidenceAvailability.hasAvailableMovementEvidence(any(), any(), any()))
                .thenThrow(new EvidenceDependencyException("timeout"));

        assertThrows(EvidenceDependencyException.class, () -> movements.submitMovement(
                new SubmitMovementCommand(draft.getId(), "Bearer user-token", UUID.randomUUID()), false));

        assertNoSubmitEffects(draft.getId());
    }

    private Movement adjustment(MovementType type) {
        UUID productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory.product_ref(product_id,sku,product_type,storage_unit_code,base_unit_code) VALUES (?,?,'MATERIAL','EA','EA')",
                productId, "SKU-" + productId.toString().substring(0, 8));
        jdbc.update("INSERT INTO inventory.location(id,code,name,location_type) VALUES (?,?,'Ajustes','ALMACEN')",
                locationId, "LOC-" + locationId.toString().substring(0, 8));
        UUID origin = type == MovementType.ADJUST_NEGATIVE ? locationId : null;
        UUID destination = type == MovementType.ADJUST_POSITIVE ? locationId : null;
        CreateMovementCommand.Detail detail = new CreateMovementCommand.Detail(productId, BigDecimal.ONE, "EA",
                origin, destination, null, null, "PEN", BigDecimal.ONE, null, null, null);
        return movements.createMovement(new CreateMovementCommand(type, null, null, null, null,
                "ADJUSTMENT", UUID.randomUUID().toString(), "conteo", null, List.of(detail)), UUID.randomUUID());
    }

    private void assertNoSubmitEffects(UUID movementId) {
        assertEquals("DRAFT", status(movementId));
        assertEquals(0L, count("SELECT count(*) FROM inventory.movement_authorization WHERE movement_id=?", movementId));
        assertEquals(0L, count("""
                SELECT count(*)
                FROM inventory.stock_balance s
                JOIN inventory.movement_detail d ON d.product_id = s.product_id
                    AND s.location_id IN (d.origin_location_id, d.destination_location_id)
                WHERE d.movement_id = ?
                """, movementId));
        assertEquals(0L, count("SELECT count(*) FROM inventory.outbox_event WHERE aggregate_id=?", movementId));
    }

    private String status(UUID movementId) {
        return jdbc.queryForObject("SELECT status FROM inventory.movement WHERE id=?", String.class, movementId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
