package utp.siga.inventory.domain.model;

public enum MovementStatus {
    DRAFT,
    PENDING_AUTHORIZATION,
    AUTHORIZED,
    CONFIRMED,
    REJECTED,
    CANCELLED
}
