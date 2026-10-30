package utp.siga.inventory.infrastructure.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Converts an already validated JWT subject into Inventory's trusted actor identifier. */
@Component
public class JwtAuthenticatedActorAdapter {

    public UUID actorUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new IllegalArgumentException("JWT subject is required");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT subject must be an IAM user UUID", exception);
        }
    }
}
