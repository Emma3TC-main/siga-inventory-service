package utp.siga.inventory.application.usecase;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utp.siga.inventory.application.command.CreateMovementCommand;
import utp.siga.inventory.application.command.SubmitMovementCommand;
import utp.siga.inventory.application.exception.EvidenceDependencyException;
import utp.siga.inventory.application.exception.EvidenceRequiredException;
import utp.siga.inventory.application.port.in.MovementUseCase;
import utp.siga.inventory.application.port.out.EvidenceAvailabilityPort;
import utp.siga.inventory.application.port.out.MovementRepository;
import utp.siga.inventory.application.port.out.MovementConfirmationPort;
import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.application.port.out.ProductRefPort;
import utp.siga.inventory.application.port.out.SupplierRefPort;
import utp.siga.inventory.application.port.out.LocationActivityPort;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementDetail;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.UnitConversionRef;

@Service
public class DefaultMovementUseCase implements MovementUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMovementUseCase.class);

    private final MovementRepository movementRepository;
    private final ProductRefPort productRefPort;
    private final SupplierRefPort supplierRefPort;
    private final Clock clock;
    private final MovementConfirmationPort movementConfirmationPort;
    private final EvidenceAvailabilityPort evidenceAvailability;
    private final LocationActivityPort locationActivity;

    @Autowired
    public DefaultMovementUseCase(MovementRepository movementRepository,
                                  ProductRefPort productRefPort,
                                  SupplierRefPort supplierRefPort,
                                  Clock clock,
                                  MovementConfirmationPort movementConfirmationPort,
                                  EvidenceAvailabilityPort evidenceAvailability,
                                  LocationActivityPort locationActivity) {
        this.movementRepository = movementRepository;
        this.productRefPort = productRefPort;
        this.supplierRefPort = supplierRefPort;
        this.clock = clock;
        this.movementConfirmationPort = movementConfirmationPort;
        this.evidenceAvailability = evidenceAvailability;
        this.locationActivity = locationActivity;
    }

    public DefaultMovementUseCase(MovementRepository movementRepository, ProductRefPort productRefPort,
            SupplierRefPort supplierRefPort, Clock clock, MovementConfirmationPort movementConfirmationPort,
            EvidenceAvailabilityPort evidenceAvailability) {
        this(movementRepository, productRefPort, supplierRefPort, clock, movementConfirmationPort,
                evidenceAvailability, ignored -> true);
    }

    public DefaultMovementUseCase(MovementRepository movementRepository,
                                  ProductRefPort productRefPort,
                                  SupplierRefPort supplierRefPort,
                                  Clock clock) {
        this(movementRepository, productRefPort, supplierRefPort, clock, null,
                (movementId, bearerToken, correlationId) -> {
                    throw new EvidenceDependencyException("Evidence availability is not configured");
                });
    }

    public DefaultMovementUseCase(MovementRepository movementRepository,
                                  ProductRefPort productRefPort,
                                  SupplierRefPort supplierRefPort,
                                  Clock clock,
                                  EvidenceAvailabilityPort evidenceAvailability) {
        this(movementRepository, productRefPort, supplierRefPort, clock, null, evidenceAvailability);
    }

    @Override
    @Transactional
    public Movement createMovement(CreateMovementCommand command, UUID actorUserId) {
        if (actorUserId == null) {
            throw new IllegalArgumentException("authenticated actorUserId is required");
        }
        Instant createdAt = clock.instant();
        Movement.Builder builder = new Movement.Builder()
            .type(command.movementType())
            .movementCode(movementRepository.nextMovementCode(createdAt))
            .createdAt(createdAt)
            .registeredByUserId(actorUserId)
            .requestedByUserId(command.requestedByUserId())
            .costCenterCode(command.costCenterCode())
            .costCenterNameSnapshot(command.costCenterNameSnapshot())
            .referenceDocType(command.referenceDocType())
            .referenceDocNumber(command.referenceDocNumber())
            .reason(command.reason())
            .notes(command.notes())
            .isSensitive(false);

        if (command.supplierId() != null) {
            supplierRefPort.getSupplier(command.supplierId()).ifPresentOrElse(
                supplier -> {
                    if (!supplier.active()) {
                        throw new IllegalArgumentException("Supplier is inactive in inventory projection");
                    }
                    builder.supplierId(supplier.id());
                    builder.supplierNameSnapshot(supplier.businessName());
                    builder.supplierTaxIdSnapshot(supplier.taxId());
                },
                () -> { throw new IllegalArgumentException("Supplier not found or not synced in inventory"); }
            );
        }

        if (command.details() == null || command.details().isEmpty()) {
            throw new IllegalArgumentException("at least one movement detail is required");
        }
        int lineNumber = 1;
        for (CreateMovementCommand.Detail detailCmd : command.details()) {
            ProductRef product = productRefPort.getProduct(detailCmd.productId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found or not synced in inventory: " + detailCmd.productId()));
            if (!product.active()) {
                throw new IllegalArgumentException("Product is inactive in inventory projection: " + detailCmd.productId());
            }
            BigDecimal conversionFactor = conversionToBase(product, detailCmd.unitCode());
            requireActiveLocations(command.movementType(), detailCmd.originLocationId(),
                    detailCmd.destinationLocationId());

            MovementDetail detail = new MovementDetail(
                null,
                lineNumber++,
                product.id(),
                detailCmd.enteredQuantity(),
                detailCmd.unitCode(),
                conversionFactor,
                detailCmd.originLocationId(),
                detailCmd.destinationLocationId(),
                detailCmd.lotId(),
                detailCmd.assetId(),
                detailCmd.originalCurrency(),
                detailCmd.originalUnitCost(),
                detailCmd.exchangeRate(),
                detailCmd.exchangeRateDate(),
                detailCmd.exchangeRateSource()
            );
            builder.addDetail(detail);
        }

        Movement movement = builder.build();
        movementRepository.save(movement);
        return movement;
    }

    @Override
    public Movement submitMovement(SubmitMovementCommand command, boolean actorCanAuthorize) {
        Movement movement = movementRepository.findById(command.movementId())
            .orElseThrow(() -> new IllegalArgumentException("Movement not found"));
        movement.getDetails().forEach(detail -> requireActiveLocations(movement.getType(),
                detail.getOriginLocationId(), detail.getDestinationLocationId()));
        requireAdjustmentEvidence(movement, command);
        Sensitivity sensitivity = evaluateSensitivity(movement);
        movement.evaluateSensitivity(sensitivity.sensitive(), sensitivity.reason());
        if (sensitivity.sensitive() || !actorCanAuthorize) {
            movement.submit();
            movementRepository.save(movement);
        }
        return movement;
    }

    public Movement submitMovement(SubmitMovementCommand command) {
        return submitMovement(command, true);
    }

    @Override
    @Transactional
    public Movement authorizeMovement(utp.siga.inventory.application.command.AuthorizeMovementCommand command,
            UUID actorUserId, boolean stepUpVerified) {
        Movement movement = movementRepository.findById(command.movementId())
            .orElseThrow(() -> new IllegalArgumentException("Movement not found"));

        movement.authorize(actorUserId, stepUpVerified, command.reason());

        movementRepository.save(movement);
        return movement;
    }

    @Override
    @Transactional
    public Movement rejectMovement(utp.siga.inventory.application.command.RejectMovementCommand command,
            UUID actorUserId, boolean stepUpVerified) {
        Movement movement = movementRepository.findById(command.movementId())
            .orElseThrow(() -> new IllegalArgumentException("Movement not found"));

        movement.reject(actorUserId, stepUpVerified, command.reason());

        movementRepository.save(movement);
        return movement;
    }

    @Override
    @Transactional
    public Movement cancelMovement(utp.siga.inventory.application.command.CancelMovementCommand command) {
        Movement movement = movementRepository.findById(command.movementId())
            .orElseThrow(() -> new IllegalArgumentException("Movement not found"));

        movement.cancel();

        movementRepository.save(movement);
        return movement;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Movement confirmMovement(ConfirmMovementCommand command) {
        if (movementConfirmationPort == null) {
            throw new IllegalStateException("Movement confirmation persistence is not configured");
        }
        return movementConfirmationPort.confirm(command);
    }

    private BigDecimal conversionToBase(ProductRef product, String unitCode) {
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("unitCode is required");
        }
        if (product.baseUnitCode().equals(unitCode)) {
            return BigDecimal.ONE;
        }
        return product.conversions().stream()
                .filter(UnitConversionRef::active)
                .filter(conversion -> unitCode.equals(conversion.fromUnitCode()))
                .filter(conversion -> product.baseUnitCode().equals(conversion.toUnitCode()))
                .map(UnitConversionRef::factor)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active direct conversion to base unit for product " + product.id() + " and unit " + unitCode));
    }

    private void requireActiveLocations(utp.siga.inventory.domain.model.MovementType type,
            UUID originId, UUID destinationId) {
        if ((type == utp.siga.inventory.domain.model.MovementType.EXIT
                || type == utp.siga.inventory.domain.model.MovementType.TRANSFER
                || type == utp.siga.inventory.domain.model.MovementType.ADJUST_NEGATIVE)
                && !locationActivity.isActive(originId)) {
            throw new IllegalArgumentException("Origin location not found or inactive");
        }
        if ((type == utp.siga.inventory.domain.model.MovementType.ENTRY
                || type == utp.siga.inventory.domain.model.MovementType.TRANSFER
                || type == utp.siga.inventory.domain.model.MovementType.ADJUST_POSITIVE)
                && !locationActivity.isActive(destinationId)) {
            throw new IllegalArgumentException("Destination location not found or inactive");
        }
    }

    private void requireAdjustmentEvidence(Movement movement, SubmitMovementCommand command) {
        if (movement.getType() != utp.siga.inventory.domain.model.MovementType.ADJUST_POSITIVE
                && movement.getType() != utp.siga.inventory.domain.model.MovementType.ADJUST_NEGATIVE) {
            return;
        }
        UUID correlationId = command.correlationId() == null ? UUID.randomUUID() : command.correlationId();
        boolean available;
        try {
            available = evidenceAvailability.hasAvailableMovementEvidence(
                    movement.getId(), command.bearerToken(), correlationId);
        } catch (EvidenceDependencyException exception) {
            LOGGER.warn("Evidence precondition dependency failed correlationId={} movementId={} movementType={} category=DEPENDENCY_FAILURE",
                    correlationId, movement.getId(), movement.getType());
            throw exception;
        }
        if (!available) {
            LOGGER.info("Evidence precondition rejected correlationId={} movementId={} movementType={} category=NOT_AVAILABLE",
                    correlationId, movement.getId(), movement.getType());
            throw new EvidenceRequiredException();
        }
    }

    private Sensitivity evaluateSensitivity(Movement movement) {
        return switch (movement.getType()) {
            case ADJUST_POSITIVE, ADJUST_NEGATIVE -> new Sensitivity(true, "INVENTORY_ADJUSTMENT");
            case EXIT -> hasAsset(movement) ? new Sensitivity(true, "SERIALIZED_ASSET_EXIT") : Sensitivity.notSensitive();
            case TRANSFER -> hasAsset(movement) ? new Sensitivity(true, "SERIALIZED_ASSET_TRANSFER") : Sensitivity.notSensitive();
            case ENTRY -> Sensitivity.notSensitive();
        };
    }

    private boolean hasAsset(Movement movement) {
        return movement.getDetails().stream().anyMatch(detail -> detail.getAssetId() != null);
    }

    private record Sensitivity(boolean sensitive, String reason) {
        static Sensitivity notSensitive() {
            return new Sensitivity(false, null);
        }
    }
}
