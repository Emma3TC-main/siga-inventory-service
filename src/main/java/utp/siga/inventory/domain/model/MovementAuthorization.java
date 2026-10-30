package utp.siga.inventory.domain.model;

import java.time.Instant;
import java.util.UUID;

public class MovementAuthorization {
    private final UUID id;
    private final Instant requestedAt;
    private Instant decidedAt;
    private UUID authorizedByUserId;
    private String decision;
    private boolean stepUpVerified;
    private String reason;

    public MovementAuthorization(UUID id, Instant requestedAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.requestedAt = requestedAt != null ? requestedAt : Instant.now();
    }

    public static MovementAuthorization rehydrate(UUID id, Instant requestedAt, Instant decidedAt,
            UUID authorizedByUserId, String decision, boolean stepUpVerified, String reason) {
        MovementAuthorization authorization = new MovementAuthorization(id, requestedAt);
        authorization.decidedAt = decidedAt;
        authorization.authorizedByUserId = authorizedByUserId;
        authorization.decision = decision;
        authorization.stepUpVerified = stepUpVerified;
        authorization.reason = reason;
        return authorization;
    }

    public void approve(UUID userId, boolean stepUpVerified, String reason) {
        if (this.decision != null) {
            throw new IllegalStateException("Authorization already decided");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required for approval");
        }
        if (!stepUpVerified) {
            throw new IllegalArgumentException("recent step-up verification is required for approval");
        }
        this.decision = "APPROVED";
        this.authorizedByUserId = userId;
        this.stepUpVerified = stepUpVerified;
        this.reason = reason;
        this.decidedAt = Instant.now();
    }

    public void reject(UUID userId, boolean stepUpVerified, String reason) {
        if (this.decision != null) {
            throw new IllegalStateException("Authorization already decided");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required for rejection");
        }
        if (!stepUpVerified) {
            throw new IllegalArgumentException("recent step-up verification is required for rejection");
        }
        this.decision = "REJECTED";
        this.authorizedByUserId = userId;
        this.stepUpVerified = stepUpVerified;
        this.reason = reason;
        this.decidedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getAuthorizedByUserId() { return authorizedByUserId; }
    public String getDecision() { return decision; }
    public boolean isStepUpVerified() { return stepUpVerified; }
    public String getReason() { return reason; }
}
