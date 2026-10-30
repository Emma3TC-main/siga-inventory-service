package utp.siga.inventory.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import utp.siga.inventory.application.exception.EvidenceDependencyException;
import utp.siga.inventory.application.exception.EvidenceRequiredException;
import utp.siga.inventory.application.port.out.EvidenceAvailabilityPort;
import utp.siga.inventory.application.command.AuthorizeMovementCommand;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.RejectMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.port.out.MovementRepository;
import utp.siga.inventory.application.port.out.ProductRefPort;
import utp.siga.inventory.application.port.out.SupplierRefPort;
import utp.siga.inventory.application.port.out.LocationActivityPort;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementStatus;
import utp.siga.inventory.domain.model.MovementType;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.SupplierRef;
import utp.siga.inventory.domain.model.UnitConversionRef;

class DefaultMovementUseCaseTest {
    private MovementRepository movementRepository;
    private ProductRefPort productRefPort;
    private SupplierRefPort supplierRefPort;
    private EvidenceAvailabilityPort evidenceAvailability;
    private DefaultMovementUseCase useCase;

    @BeforeEach
    void setUp() {
        movementRepository = Mockito.mock(MovementRepository.class);
        productRefPort = Mockito.mock(ProductRefPort.class);
        supplierRefPort = Mockito.mock(SupplierRefPort.class);
        evidenceAvailability = Mockito.mock(EvidenceAvailabilityPort.class);
        Mockito.when(evidenceAvailability.hasAvailableMovementEvidence(any(), any(), any())).thenReturn(true);
        useCase = new DefaultMovementUseCase(movementRepository, productRefPort, supplierRefPort,
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC), evidenceAvailability);
        Mockito.when(movementRepository.nextMovementCode(any())).thenReturn("MOV-2026-00001");
    }

    @Test
    void createMovementResolvesActiveDirectConversionFromProjection() {
        UUID productId = UUID.randomUUID();
        Mockito.when(productRefPort.getProduct(productId)).thenReturn(Optional.of(product(productId, true)));

        Movement movement = useCase.createMovement(command(productId, "BOX", null), UUID.randomUUID());

        assertEquals(new BigDecimal("12.000000"), movement.getDetails().getFirst().getConversionFactor());
        assertEquals(new BigDecimal("120.000000"), movement.getDetails().getFirst().getStockQuantity());
        Mockito.verify(movementRepository).save(any(Movement.class));
    }

    @Test
    void createMovementUsesOneOnlyForTheBaseUnit() {
        UUID productId = UUID.randomUUID();
        Mockito.when(productRefPort.getProduct(productId)).thenReturn(Optional.of(product(productId, true)));

        Movement movement = useCase.createMovement(command(productId, "EA", null), UUID.randomUUID());

        assertEquals(BigDecimal.ONE, movement.getDetails().getFirst().getConversionFactor());
    }

    @Test
    void createMovementRejectsInactiveProductOrMissingDirectConversion() {
        UUID inactive = UUID.randomUUID();
        Mockito.when(productRefPort.getProduct(inactive)).thenReturn(Optional.of(product(inactive, false)));
        assertThrows(IllegalArgumentException.class, () -> useCase.createMovement(command(inactive, "EA", null), UUID.randomUUID()));

        UUID missing = UUID.randomUUID();
        Mockito.when(productRefPort.getProduct(missing)).thenReturn(Optional.of(product(missing, true)));
        assertThrows(IllegalArgumentException.class, () -> useCase.createMovement(command(missing, "PAL", null), UUID.randomUUID()));
    }

    @Test
    void createMovementRejectsInactiveRequiredLocation() {
        UUID productId = UUID.randomUUID();
        Mockito.when(productRefPort.getProduct(productId)).thenReturn(Optional.of(product(productId, true)));
        LocationActivityPort locationActivity = Mockito.mock(LocationActivityPort.class);
        Mockito.when(locationActivity.isActive(any())).thenReturn(false);
        DefaultMovementUseCase guarded = new DefaultMovementUseCase(movementRepository, productRefPort,
                supplierRefPort, Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC),
                null, evidenceAvailability, locationActivity);
        assertThrows(IllegalArgumentException.class,
                () -> guarded.createMovement(command(productId, "EA", null), UUID.randomUUID()));
    }

    @Test
    void createMovementSnapshotsOnlyAnActiveSupplierProjection() {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Mockito.when(productRefPort.getProduct(productId)).thenReturn(Optional.of(product(productId, true)));
        Mockito.when(supplierRefPort.getSupplier(supplierId)).thenReturn(Optional.of(
                new SupplierRef(supplierId, "SUP-1", "20123456789", "Proveedor", true, 3, Instant.now())));

        Movement movement = useCase.createMovement(command(productId, "EA", supplierId), UUID.randomUUID());

        assertEquals("Proveedor", movement.getSupplierNameSnapshot());
        assertEquals("20123456789", movement.getSupplierTaxIdSnapshot());
    }

    @Test
    void submitEvaluatesApprovedSensitivityPolicy() {
        Movement movement = draft(MovementType.ADJUST_POSITIVE, null);
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));
        Movement submitted = useCase.submitMovement(new SubmitMovementCommand(movement.getId()));
        assertEquals(MovementStatus.PENDING_AUTHORIZATION, submitted.getStatus());
        assertEquals("INVENTORY_ADJUSTMENT", submitted.getSensitivityReason());

        Movement negativeAdjustment = draft(MovementType.ADJUST_NEGATIVE, null);
        Mockito.when(movementRepository.findById(negativeAdjustment.getId())).thenReturn(Optional.of(negativeAdjustment));
        assertEquals("INVENTORY_ADJUSTMENT", useCase.submitMovement(
                new SubmitMovementCommand(negativeAdjustment.getId())).getSensitivityReason());

        Movement assetExit = draft(MovementType.EXIT, UUID.randomUUID());
        Mockito.when(movementRepository.findById(assetExit.getId())).thenReturn(Optional.of(assetExit));
        assertEquals("SERIALIZED_ASSET_EXIT", useCase.submitMovement(new SubmitMovementCommand(assetExit.getId())).getSensitivityReason());

        Movement assetTransfer = draft(MovementType.TRANSFER, UUID.randomUUID());
        Mockito.when(movementRepository.findById(assetTransfer.getId())).thenReturn(Optional.of(assetTransfer));
        assertEquals("SERIALIZED_ASSET_TRANSFER", useCase.submitMovement(new SubmitMovementCommand(assetTransfer.getId())).getSensitivityReason());

        Movement ordinaryExit = draft(MovementType.EXIT, null);
        Mockito.when(movementRepository.findById(ordinaryExit.getId())).thenReturn(Optional.of(ordinaryExit));
        Movement evaluated = useCase.submitMovement(new SubmitMovementCommand(ordinaryExit.getId()));
        assertEquals(MovementStatus.DRAFT, evaluated.getStatus());
        assertEquals(false, evaluated.isSensitive());
    }

    @Test
    void submitByActorWithoutAuthorizePermissionRequiresApprovalEvenWhenNotSensitive() {
        Movement movement = draft(MovementType.EXIT, null);
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));

        Movement submitted = useCase.submitMovement(new SubmitMovementCommand(movement.getId()), false);

        assertEquals(MovementStatus.PENDING_AUTHORIZATION, submitted.getStatus());
        assertEquals(false, submitted.isSensitive());
        Mockito.verify(movementRepository).save(movement);
    }

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ADJUST_POSITIVE", "ADJUST_NEGATIVE"})
    void adjustmentSubmitRequiresAvailableEvidence(MovementType type) {
        Movement movement = draft(type, null);
        UUID correlationId = UUID.randomUUID();
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));
        Mockito.when(evidenceAvailability.hasAvailableMovementEvidence(
                movement.getId(), "Bearer user-token", correlationId)).thenReturn(false);

        assertThrows(EvidenceRequiredException.class, () -> useCase.submitMovement(
                new SubmitMovementCommand(movement.getId(), "Bearer user-token", correlationId), false));

        assertEquals(MovementStatus.DRAFT, movement.getStatus());
        assertFalse(movement.isSensitive());
        Mockito.verify(movementRepository, Mockito.never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ADJUST_POSITIVE", "ADJUST_NEGATIVE"})
    void adjustmentSubmitProgressesWhenEvidenceIsAvailable(MovementType type) {
        Movement movement = draft(type, null);
        UUID correlationId = UUID.randomUUID();
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));

        Movement submitted = useCase.submitMovement(
                new SubmitMovementCommand(movement.getId(), "Bearer user-token", correlationId), false);

        assertEquals(MovementStatus.PENDING_AUTHORIZATION, submitted.getStatus());
        assertEquals("INVENTORY_ADJUSTMENT", submitted.getSensitivityReason());
        Mockito.verify(evidenceAvailability).hasAvailableMovementEvidence(
                movement.getId(), "Bearer user-token", correlationId);
        Mockito.verify(movementRepository).save(movement);
    }

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ADJUST_POSITIVE", "ADJUST_NEGATIVE"})
    void adjustmentSubmitFailsClosedWhenEvidenceDependencyFails(MovementType type) {
        Movement movement = draft(type, null);
        UUID correlationId = UUID.randomUUID();
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));
        Mockito.when(evidenceAvailability.hasAvailableMovementEvidence(
                movement.getId(), "Bearer user-token", correlationId))
                .thenThrow(new EvidenceDependencyException("unavailable"));

        assertThrows(EvidenceDependencyException.class, () -> useCase.submitMovement(
                new SubmitMovementCommand(movement.getId(), "Bearer user-token", correlationId), false));

        assertEquals(MovementStatus.DRAFT, movement.getStatus());
        Mockito.verify(movementRepository, Mockito.never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"ENTRY", "EXIT", "TRANSFER"})
    void optionalMovementTypesDoNotCallEvidence(MovementType type) {
        Movement movement = draft(type, null);
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));

        useCase.submitMovement(new SubmitMovementCommand(movement.getId()), false);

        Mockito.verifyNoInteractions(evidenceAvailability);
    }

    @Test
    void makerCannotAuthorizeOrRejectOwnMovement() {
        UUID maker = UUID.randomUUID();
        Movement movement = pendingMovement(maker);
        Mockito.when(movementRepository.findById(movement.getId())).thenReturn(Optional.of(movement));

        assertThrows(utp.siga.inventory.domain.exception.InvalidMovementStateException.class,
                () -> useCase.authorizeMovement(new AuthorizeMovementCommand(movement.getId(), "ok"), maker, true));
        assertThrows(utp.siga.inventory.domain.exception.InvalidMovementStateException.class,
                () -> useCase.rejectMovement(new RejectMovementCommand(movement.getId(), "no"), maker, true));
    }

    @Test
    void authorizeAndRejectRequireAlreadyValidatedStepUpEvidence() {
        Movement authorization = sensitivePending();
        Mockito.when(movementRepository.findById(authorization.getId())).thenReturn(Optional.of(authorization));
        assertThrows(IllegalArgumentException.class, () -> useCase.authorizeMovement(
                new AuthorizeMovementCommand(authorization.getId(), "ok"), UUID.randomUUID(), false));

        Movement rejection = sensitivePending();
        Mockito.when(movementRepository.findById(rejection.getId())).thenReturn(Optional.of(rejection));
        Movement rejected = useCase.rejectMovement(
                new RejectMovementCommand(rejection.getId(), "no"), UUID.randomUUID(), true);
        assertEquals(MovementStatus.REJECTED, rejected.getStatus());
    }

    private static ProductRef product(UUID id, boolean active) {
        return new ProductRef(id, "SKU-" + id, "MATERIAL", "BOX", "EA", BigDecimal.ONE,
                false, false, false, false, active, 4, Instant.now(),
                List.of(new UnitConversionRef("BOX", "EA", new BigDecimal("12.000000"), true)));
    }

    private static CreateMovementCommand command(UUID productId, String unitCode, UUID supplierId) {
        CreateMovementCommand.Detail detail = new CreateMovementCommand.Detail(productId, new BigDecimal("10"), unitCode,
                null, UUID.randomUUID(), null, null, "PEN", null, null, null, null);
        return new CreateMovementCommand(MovementType.ENTRY, UUID.randomUUID(), null, null, supplierId,
                null, null, "Reason", "Notes", List.of(detail));
    }

    private static Movement sensitivePending() {
        return pendingMovement(UUID.randomUUID());
    }

    private static Movement pendingMovement(UUID maker) {
        Movement movement = new Movement.Builder().movementCode("MOV-2026-00001").type(MovementType.ENTRY).registeredByUserId(maker)
                .addDetail(new utp.siga.inventory.domain.model.MovementDetail(null, 1, UUID.randomUUID(), BigDecimal.ONE,
                        "EA", BigDecimal.ONE, null, UUID.randomUUID(), null, null, "PEN", null, null, null, null))
                .isSensitive(true).sensitivityReason("review").build();
        movement.submit();
        return movement;
    }

    private static Movement draft(MovementType type, UUID assetId) {
        UUID origin = type == MovementType.ENTRY ? null : UUID.randomUUID();
        UUID destination = type == MovementType.EXIT ? null : UUID.randomUUID();
        return new Movement.Builder().movementCode("MOV-2026-00001").type(type).registeredByUserId(UUID.randomUUID())
                .reason(type == MovementType.ADJUST_POSITIVE || type == MovementType.ADJUST_NEGATIVE ? "adjustment" : null)
                .addDetail(new utp.siga.inventory.domain.model.MovementDetail(null, 1, UUID.randomUUID(), BigDecimal.ONE,
                        "EA", BigDecimal.ONE, origin, destination, null, assetId, "PEN", null, null, null, null))
                .build();
    }
}
