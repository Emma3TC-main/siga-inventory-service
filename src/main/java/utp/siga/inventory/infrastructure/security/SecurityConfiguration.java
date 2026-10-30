package utp.siga.inventory.infrastructure.security;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import utp.siga.inventory.presentation.error.Problems;

@Configuration
public class SecurityConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${inventory.security.jwks-uri}") String jwks,
            @Value("${inventory.security.issuer}") String issuer,
            @Value("${inventory.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> jwt.getAudience().contains(audience)
                && jwt.getExpiresAt() != null && jwt.getSubject() != null
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/inventory", "/api/v1/inventory/**",
                        "/api/v1/movements", "/api/v1/movements/**",
                        "/api/v1/lots", "/api/v1/assets")
                    .hasAuthority("INVENTORY_READ")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/lots", "/api/v1/lots/*")
                    .hasAnyAuthority("INVENTORY_READ", "TRACEABILITY_MANAGE")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/assets", "/api/v1/assets/*")
                    .hasAnyAuthority("INVENTORY_READ", "TRACEABILITY_MANAGE")
                .requestMatchers(HttpMethod.POST, "/api/v1/lots", "/api/v1/lots/*/status")
                    .hasAuthority("TRACEABILITY_MANAGE")
                .requestMatchers(HttpMethod.PUT, "/api/v1/lots/*")
                    .hasAuthority("TRACEABILITY_MANAGE")
                .requestMatchers(HttpMethod.POST, "/api/v1/assets", "/api/v1/assets/*/status")
                    .hasAuthority("TRACEABILITY_MANAGE")
                .requestMatchers(HttpMethod.PUT, "/api/v1/assets/*")
                    .hasAuthority("TRACEABILITY_MANAGE")
                .requestMatchers(HttpMethod.GET, "/api/v1/locations", "/api/v1/locations/*")
                    .hasAnyAuthority("INVENTORY_READ", "LOCATION_MANAGE")
                .requestMatchers(HttpMethod.POST, "/api/v1/locations", "/api/v1/locations/*/deactivate",
                        "/api/v1/locations/*/reactivate")
                    .hasAuthority("LOCATION_MANAGE")
                .requestMatchers(HttpMethod.PUT, "/api/v1/locations/*")
                    .hasAuthority("LOCATION_MANAGE")
                .requestMatchers("/api/v1/counts", "/api/v1/counts/**")
                    .hasAuthority("INVENTORY_COUNT")
                .requestMatchers(HttpMethod.POST, "/api/v1/movements", "/api/v1/movements/*/submit",
                         "/api/v1/movements/*/cancel", "/api/v1/movements/*/confirm")
                    .hasAnyAuthority("MOVEMENT_CREATE", "INVENTORY_ADJUST")
                .requestMatchers(HttpMethod.POST, "/api/v1/movements/*/authorize", "/api/v1/movements/*/reject")
                    .hasAuthority("MOVEMENT_AUTHORIZE")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, x) -> Problems.write(req, res, 401, "AUTH_INVALID"))
                .accessDeniedHandler((req, res, x) -> Problems.write(req, res, 403, "AUTH_FORBIDDEN")))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req, res, x) -> Problems.write(req, res, 401, "AUTH_INVALID"))
                .accessDeniedHandler((req, res, x) -> Problems.write(req, res, 403, "AUTH_FORBIDDEN")));
        return http.build();
    }
}
