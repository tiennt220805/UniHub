package com.unihub.shared.infrastructure.security;

import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
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
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT authentication (auth-and-identity.md §3). Every request is verified from the token
 * alone: no session, no database or Redis lookup. Deny by default; only the paths listed below are
 * open. Per-endpoint authorization ({@code hasAuthority}) arrives with M-03.
 */
@Configuration
@EnableConfigurationProperties(AuthJwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(
                        AbstractHttpConfigurer
                                ::disable) // bearer tokens, no cookies: nothing for CSRF to ride
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
                                        // generateOpenApiDocs / contract-diff read the live spec.
                                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
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
