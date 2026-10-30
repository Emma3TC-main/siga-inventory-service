package utp.siga.inventory.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import utp.siga.inventory.domain.exception.InvalidMovementFieldException;
import static org.junit.jupiter.api.Assertions.*;

class MovementDetailTest {

    @Test
    void create_validDetail() {
        MovementDetail detail = new MovementDetail(
            null, 1, UUID.randomUUID(), new BigDecimal("10.5"), "UNIT", BigDecimal.ONE,
            null, null, null, null, "PEN", null, null, null, null
        );

        assertEquals(new BigDecimal("10.5"), detail.getStockQuantity());
    }

    @Test
    void create_withConversionFactor_calculatesStockQuantity() {
        MovementDetail detail = new MovementDetail(
            null, 1, UUID.randomUUID(), new BigDecimal("10"), "BOX", new BigDecimal("12"),
            null, null, null, null, "PEN", null, null, null, null
        );

        assertEquals(new BigDecimal("120"), detail.getStockQuantity());
    }

    @Test
    void invalid_negativeQuantity_throwsException() {
        assertThrows(InvalidMovementFieldException.class, () ->
            new MovementDetail(
                null, 1, UUID.randomUUID(), new BigDecimal("-5"), "UNIT", BigDecimal.ONE,
                null, null, null, null, "PEN", null, null, null, null
            )
        );
    }

    @Test
    void invalid_zeroLineNumber_throwsException() {
        assertThrows(InvalidMovementFieldException.class, () ->
            new MovementDetail(
                null, 0, UUID.randomUUID(), new BigDecimal("10"), "UNIT", BigDecimal.ONE,
                null, null, null, null, "PEN", null, null, null, null
            )
        );
    }

    @Test
    void invalid_blankUnitCode_throwsException() {
        assertThrows(InvalidMovementFieldException.class, () ->
            new MovementDetail(
                null, 1, UUID.randomUUID(), new BigDecimal("10"), " ", BigDecimal.ONE,
                null, null, null, null, "PEN", null, null, null, null
            )
        );
    }

    @Test
    void invalid_sameOriginAndDestination_throwsException() {
        UUID locId = UUID.randomUUID();
        assertThrows(InvalidMovementFieldException.class, () ->
            new MovementDetail(
                null, 1, UUID.randomUUID(), new BigDecimal("10"), "UNIT", BigDecimal.ONE,
                locId, locId, null, null, "PEN", null, null, null, null
            )
        );
    }

    @Test
    void foreignCurrency_requiresExchangeDetails() {
        // Missing exchange details
        assertThrows(InvalidMovementFieldException.class, () ->
            new MovementDetail(
                null, 1, UUID.randomUUID(), new BigDecimal("10"), "UNIT", BigDecimal.ONE,
                null, null, null, null, "USD", null, null, null, null
            )
        );

        // Valid foreign currency
        assertDoesNotThrow(() ->
            new MovementDetail(
                null, 1, UUID.randomUUID(), new BigDecimal("10"), "UNIT", BigDecimal.ONE,
                null, null, null, null, "USD", null, new BigDecimal("3.8"), LocalDate.now(), "MANUAL"
            )
        );
    }
}
