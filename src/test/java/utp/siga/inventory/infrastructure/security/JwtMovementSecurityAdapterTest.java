package utp.siga.inventory.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import utp.siga.inventory.application.command.CreateMovementCommand;

class JwtMovementSecurityAdapterTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private final JwtAuthenticatedActorAdapter actorAdapter = new JwtAuthenticatedActorAdapter();
    private final JwtStepUpValidator stepUpValidator = new JwtStepUpValidator(actorAdapter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void derivesTheActorOnlyFromUuidSubjectAndCommandsExposeNoActorField() {
        UUID actor = UUID.randomUUID();
        assertEquals(actor, actorAdapter.actorUserId(jwt(actor, List.of("pwd"), NOW.getEpochSecond())));
        assertThrows(IllegalArgumentException.class,
                () -> actorAdapter.actorUserId(jwt("not-a-uuid", List.of("pwd"), NOW.getEpochSecond())));
        assertFalse(Arrays.stream(CreateMovementCommand.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("registeredByUserId")));
        assertFalse(Arrays.stream(CreateMovementCommand.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("sensitive")));
    }

    @Test
    void acceptsRecentOtpStepUpForAuthorizerWithPermission() {
        UUID actor = UUID.randomUUID();
        assertEquals(actor, stepUpValidator.verifiedAuthorizer(jwt(actor, List.of("pwd", "otp"), NOW.minusSeconds(300).getEpochSecond())));
    }

    @Test
    void rejectsMissingOtpStaleOrMissingAuthTimeAndInvalidSubject() {
        UUID actor = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> stepUpValidator.verifiedAuthorizer(jwt(actor, List.of("pwd"), NOW.getEpochSecond())));
        assertThrows(IllegalArgumentException.class,
                () -> stepUpValidator.verifiedAuthorizer(jwt(actor, List.of("pwd", "otp"), NOW.minusSeconds(301).getEpochSecond())));
        assertThrows(IllegalArgumentException.class,
                () -> stepUpValidator.verifiedAuthorizer(jwtWithoutAuthTime(actor)));
        assertThrows(IllegalArgumentException.class,
                () -> stepUpValidator.verifiedAuthorizer(jwt("bad-subject", List.of("pwd", "otp"), NOW.getEpochSecond())));
        assertThrows(IllegalArgumentException.class,
                () -> stepUpValidator.verifiedAuthorizer(jwtWithoutAuthorizePermission(actor)));
    }

    private static Jwt jwt(UUID subject, List<String> amr, long authTime) {
        return jwt(subject.toString(), amr, authTime);
    }

    private static Jwt jwt(String subject, List<String> amr, long authTime) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(600))
                .claim("permissions", List.of("MOVEMENT_AUTHORIZE"))
                .claim("amr", amr)
                .claim("auth_time", authTime)
                .build();
    }

    private static Jwt jwtWithoutAuthTime(UUID subject) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject(subject.toString())
                .issuedAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(600))
                .claim("permissions", List.of("MOVEMENT_AUTHORIZE"))
                .claim("amr", List.of("pwd", "otp"))
                .build();
    }

    private static Jwt jwtWithoutAuthorizePermission(UUID subject) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject(subject.toString())
                .issuedAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(600))
                .claim("permissions", List.of("MOVEMENT_CREATE"))
                .claim("amr", List.of("pwd", "otp"))
                .claim("auth_time", NOW.getEpochSecond())
                .build();
    }
}
