package utp.siga.inventory.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import utp.siga.inventory.domain.exception.InvalidMovementStateException;

public class Movement {
    private final UUID id;
    private final String movementCode;
    private final MovementType type;
    private MovementStatus status;
    private final UUID registeredByUserId;
    private UUID requestedByUserId;

    // Extracted optional snapshots
    private String costCenterCode;
    private String costCenterNameSnapshot;
    private UUID supplierId;
    private String supplierNameSnapshot;
    private String supplierTaxIdSnapshot;
    private String referenceDocType;
    private String referenceDocNumber;
    private String reason;
    private String notes;
    private boolean isSensitive;
    private String sensitivityReason;

    private final List<MovementDetail> details;
    private final List<MovementAuthorization> authorizations;

    private Instant createdAt;
    private Instant submittedAt;
    private Instant confirmedAt;

    private Movement(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.movementCode = builder.movementCode;
        this.type = builder.type;
        this.status = builder.status != null ? builder.status : MovementStatus.DRAFT;
        this.registeredByUserId = builder.registeredByUserId;
        this.requestedByUserId = builder.requestedByUserId;
        this.costCenterCode = builder.costCenterCode;
        this.costCenterNameSnapshot = builder.costCenterNameSnapshot;
        this.supplierId = builder.supplierId;
        this.supplierNameSnapshot = builder.supplierNameSnapshot;
        this.supplierTaxIdSnapshot = builder.supplierTaxIdSnapshot;
        this.referenceDocType = builder.referenceDocType;
        this.referenceDocNumber = builder.referenceDocNumber;
        this.reason = builder.reason;
        this.notes = builder.notes;
        this.isSensitive = builder.isSensitive;
        this.sensitivityReason = builder.sensitivityReason;
        this.details = new ArrayList<>(builder.details);
        this.authorizations = builder.authorizations != null ? new ArrayList<>(builder.authorizations) : new ArrayList<>();
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.submittedAt = builder.submittedAt;
        this.confirmedAt = builder.confirmedAt;
        validateInvariants();
    }

    private void validateInvariants() {
        if (type == null) throw new IllegalArgumentException("type is required");
        if (movementCode == null || movementCode.isBlank()) throw new IllegalArgumentException("movementCode is required");
        if (registeredByUserId == null) throw new IllegalArgumentException("registeredByUserId is required");
        if (details.isEmpty()) throw new IllegalArgumentException("at least one movement detail is required");
        Set<Integer> lineNumbers = new HashSet<>();
        for (MovementDetail detail : details) {
            if (detail == null) throw new IllegalArgumentException("movement detail is required");
            if (!lineNumbers.add(detail.getLineNumber())) {
                throw new IllegalArgumentException("movement detail line numbers must be unique");
            }
            if (type == MovementType.ENTRY && detail.getDestinationLocationId() == null) {
                throw new IllegalArgumentException("ENTRY details require a destination location");
            }
            if (type == MovementType.EXIT && detail.getOriginLocationId() == null) {
                throw new IllegalArgumentException("EXIT details require an origin location");
            }
            if (type == MovementType.TRANSFER
                    && (detail.getOriginLocationId() == null || detail.getDestinationLocationId() == null)) {
                throw new IllegalArgumentException("TRANSFER details require origin and destination locations");
            }
        }
        if ((type == MovementType.ADJUST_POSITIVE || type == MovementType.ADJUST_NEGATIVE)
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason is required for inventory adjustments");
        }
        if (isSensitive && (sensitivityReason == null || sensitivityReason.isBlank())) {
            throw new IllegalArgumentException("sensitivityReason is required when movement is sensitive");
        }
        if (supplierId != null && type != MovementType.ENTRY) {
            throw new IllegalArgumentException("supplierId is only valid for ENTRY movements");
        }
        if (supplierId != null && (supplierNameSnapshot == null || supplierTaxIdSnapshot == null)) {
            throw new IllegalArgumentException("supplier snapshots are required when supplierId is present");
        }
    }

    public void evaluateSensitivity(boolean isSensitive, String sensitivityReason) {
        if (this.status != MovementStatus.DRAFT) {
            throw new InvalidMovementStateException("Sensitivity can only be evaluated in DRAFT state");
        }
        this.isSensitive = isSensitive;
        this.sensitivityReason = sensitivityReason;
        validateInvariants();
    }

    public void submit() {
        if (this.status != MovementStatus.DRAFT) {
            throw new InvalidMovementStateException("Only DRAFT movements can be submitted");
        }
        this.status = MovementStatus.PENDING_AUTHORIZATION;
        this.authorizations.add(new MovementAuthorization(null, Instant.now()));
        this.submittedAt = Instant.now();
    }

    public void authorize(UUID userId, boolean stepUpVerified, String reason) {
        if (this.status != MovementStatus.PENDING_AUTHORIZATION) {
            throw new InvalidMovementStateException("Only PENDING_AUTHORIZATION movements can be authorized");
        }
        if (registeredByUserId.equals(userId)) {
            throw new InvalidMovementStateException("Maker-checker requires a different authorizer");
        }
        MovementAuthorization currentAuth = this.authorizations.stream()
            .filter(a -> a.getDecision() == null)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No pending authorization request found"));

        currentAuth.approve(userId, stepUpVerified, reason);
        this.status = MovementStatus.AUTHORIZED;
    }

    public void reject(UUID userId, boolean stepUpVerified, String reason) {
        if (this.status != MovementStatus.PENDING_AUTHORIZATION) {
            throw new InvalidMovementStateException("Only PENDING_AUTHORIZATION movements can be rejected");
        }
        if (registeredByUserId.equals(userId)) {
            throw new InvalidMovementStateException("Maker-checker requires a different reviewer");
        }
        MovementAuthorization currentAuth = this.authorizations.stream()
            .filter(a -> a.getDecision() == null)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No pending authorization request found"));

        currentAuth.reject(userId, stepUpVerified, reason);
        this.status = MovementStatus.REJECTED;
    }

    public void cancel() {
        if (this.status != MovementStatus.DRAFT && this.status != MovementStatus.PENDING_AUTHORIZATION && this.status != MovementStatus.AUTHORIZED) {
            throw new InvalidMovementStateException("Cannot cancel a movement in state " + this.status);
        }
        this.status = MovementStatus.CANCELLED;
    }

    public void confirm() {
        if (this.status == MovementStatus.DRAFT) {
            if (this.isSensitive) {
                throw new InvalidMovementStateException("Sensitive movements must be submitted for authorization first");
            }
        } else if (this.status == MovementStatus.AUTHORIZED) {
            // Can be confirmed
        } else {
            throw new InvalidMovementStateException("Cannot confirm a movement in state " + this.status);
        }

        this.status = MovementStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    // getters

    public UUID getId() { return id; }
    public String getMovementCode() { return movementCode; }
    public MovementStatus getStatus() { return status; }
    public MovementType getType() { return type; }
    public UUID getRegisteredByUserId() { return registeredByUserId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getCostCenterCode() { return costCenterCode; }
    public String getCostCenterNameSnapshot() { return costCenterNameSnapshot; }
    public UUID getSupplierId() { return supplierId; }
    public String getSupplierNameSnapshot() { return supplierNameSnapshot; }
    public String getSupplierTaxIdSnapshot() { return supplierTaxIdSnapshot; }
    public String getReferenceDocType() { return referenceDocType; }
    public String getReferenceDocNumber() { return referenceDocNumber; }
    public String getReason() { return reason; }
    public String getNotes() { return notes; }
    public boolean isSensitive() { return isSensitive; }
    public String getSensitivityReason() { return sensitivityReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public List<MovementDetail> getDetails() { return Collections.unmodifiableList(details); }
    public List<MovementAuthorization> getAuthorizations() { return Collections.unmodifiableList(authorizations); }

    public static class Builder {
        private UUID id;
        private String movementCode;
        private MovementType type;
        private MovementStatus status;
        private UUID registeredByUserId;
        private UUID requestedByUserId;
        private String costCenterCode;
        private String costCenterNameSnapshot;
        private UUID supplierId;
        private String supplierNameSnapshot;
        private String supplierTaxIdSnapshot;
        private String referenceDocType;
        private String referenceDocNumber;
        private String reason;
        private String notes;
        private boolean isSensitive;
        private String sensitivityReason;
        private List<MovementDetail> details = new ArrayList<>();
        private List<MovementAuthorization> authorizations = new ArrayList<>();
        private Instant createdAt;
        private Instant submittedAt;
        private Instant confirmedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder status(MovementStatus status) { this.status = status; return this; }
        public Builder type(MovementType type) { this.type = type; return this; }
        public Builder movementCode(String movementCode) { this.movementCode = movementCode; return this; }
        public Builder requestedByUserId(UUID requestedByUserId) { this.requestedByUserId = requestedByUserId; return this; }
        public Builder costCenterCode(String costCenterCode) { this.costCenterCode = costCenterCode; return this; }
        public Builder costCenterNameSnapshot(String costCenterNameSnapshot) { this.costCenterNameSnapshot = costCenterNameSnapshot; return this; }
        public Builder referenceDocType(String referenceDocType) { this.referenceDocType = referenceDocType; return this; }
        public Builder referenceDocNumber(String referenceDocNumber) { this.referenceDocNumber = referenceDocNumber; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder notes(String notes) { this.notes = notes; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder submittedAt(Instant submittedAt) { this.submittedAt = submittedAt; return this; }
        public Builder confirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; return this; }
        public Builder registeredByUserId(UUID registeredByUserId) { this.registeredByUserId = registeredByUserId; return this; }
        public Builder isSensitive(boolean isSensitive) { this.isSensitive = isSensitive; return this; }
        public Builder sensitivityReason(String sensitivityReason) { this.sensitivityReason = sensitivityReason; return this; }
        public Builder supplierId(UUID supplierId) { this.supplierId = supplierId; return this; }
        public Builder supplierNameSnapshot(String supplierNameSnapshot) { this.supplierNameSnapshot = supplierNameSnapshot; return this; }
        public Builder supplierTaxIdSnapshot(String supplierTaxIdSnapshot) { this.supplierTaxIdSnapshot = supplierTaxIdSnapshot; return this; }
        public Builder addDetail(MovementDetail detail) { this.details.add(detail); return this; }
        public Builder addAuthorization(MovementAuthorization auth) { this.authorizations.add(auth); return this; }

        public Movement build() {
            return new Movement(this);
        }
    }
}
