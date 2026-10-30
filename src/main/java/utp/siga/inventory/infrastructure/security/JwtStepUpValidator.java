package utp.siga.inventory.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Validates Inventory-specific step-up semantics after Resource Server JWT validation. */
@Component
public class JwtStepUpValidator {
    private static final long MAX_STEP_UP_AGE_SECONDS = 300;

    private final JwtAuthenticatedActorAdapter actorAdapter;
    private final Clock clock;

    public JwtStepUpValidator(JwtAuthenticatedActorAdapter actorAdapter, Clock clock) {
        this.actorAdapter = actorAdapter;
        this.clock = clock;
    }

    public UUID verifiedAuthorizer(Jwt jwt) {
        UUID actorUserId = actorAdapter.actorUserId(jwt);
        if (!stringClaimContains(jwt, "permissions", "MOVEMENT_AUTHORIZE")) {
            throw new IllegalArgumentException("MOVEMENT_AUTHORIZE permission is required");
        }
        if (!stringClaimContains(jwt, "amr", "otp")) {
            throw new IllegalArgumentException("JWT MFA step-up claim is required");
        }
        Number authTime = jwt.getClaim("auth_time");
        if (authTime == null) {
            throw new IllegalArgumentException("JWT auth_time is required for step-up");
        }
        long age = Instant.now(clock).getEpochSecond() - authTime.longValue();
        if (age < 0 || age > MAX_STEP_UP_AGE_SECONDS) {
            throw new IllegalArgumentException("JWT MFA step-up is not recent");
        }
        return actorUserId;
    }

    private boolean stringClaimContains(Jwt jwt, String claim, String expected) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return values != null && values.contains(expected);
    }
}
