package com.unihub.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT authentication and permission-based authorization (auth-and-identity.md §3, §5).
 * Every request is verified from the token alone: no session, no database or Redis lookup. Deny by
 * default; only the paths listed below are open. Endpoints then name the permission they need with
 * {@code @PreAuthorize("hasAuthority(...)")} on the controller (backend-coding-standards.md §11) —
 * never a role name.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthJwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            JwtAuthenticationConverter permissionsAuthenticationConverter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // bearer tokens, no cookies: nothing to ride
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // The container's internal error dispatch (e.g. a 404
                                        // body).
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        // Public catalog (packages/api-contract: listWorkshops).
                                        .requestMatchers(HttpMethod.GET, "/api/v1/workshops")
                                        .permitAll()
                                        // Public detail (getWorkshop): a token is optional, but
                                        // one that is sent is still verified (bad token → 401) and
                                        // lets the use case show a DRAFT to its managers.
                                        .requestMatchers(HttpMethod.GET, "/api/v1/workshops/*")
                                        .permitAll()
                                        // Login is how a token is obtained: no token yet.
                                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                                        .permitAll()
                                        // The gateway's webhook is called by the gateway, with
                                        // no UniHub session: trust is the signature over the raw
                                        // body, verified by the use case before anything is read.
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/webhooks/payment-gateway")
                                        .permitAll()
                                        // generateOpenApiDocs / contract-diff read the live spec.
                                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // 401 and 403 are written by the filters, so both must be problem+json here.
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler)
                                        .jwt(
                                                jwt ->
                                                        jwt.jwtAuthenticationConverter(
                                                                permissionsAuthenticationConverter)));
        return http.build();
    }

    @Bean
    ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ObjectMapper json) {
        return new ProblemDetailAuthenticationEntryPoint(json);
    }

    @Bean
    ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper json) {
        return new ProblemDetailAccessDeniedHandler(json);
    }

    /**
     * Authorities are exactly the token's {@code permissions[]}: no prefix, so {@code
     * hasAuthority('WORKSHOP_CREATE')} matches the claim value as written, and no other claim
     * (neither {@code role} nor Spring's default {@code scope}/{@code scp}) can ever grant
     * anything.
     */
    @Bean
    JwtAuthenticationConverter permissionsAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * HS256 only, pinned: a token asserting any other {@code alg} — including {@code none} — is
     * rejected before its signature is looked at. No clock-skew leeway (§4), and a token without
     * {@code exp} is refused, so no token can live forever.
     */
    @Bean
    JwtDecoder authJwtDecoder(AuthJwtProperties properties) {
        var key =
                new SecretKeySpec(
                        properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(Duration.ZERO), requireExpiry()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requireExpiry() {
        return jwt ->
                jwt.getExpiresAt() == null
                        ? OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "token has no exp claim", null))
                        : OAuth2TokenValidatorResult.success();
    }
}
