package com.unihub.shared.infrastructure.security;

import static com.unihub.shared.security.TestJwts.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-02 over real HTTP: the booted application, with its real filter chain, answers 401 to every bad
 * token <em>without ever reaching a handler</em>, and lets a good one through. "No side effect" is
 * proven with a test-only probe endpoint that counts how often it runs; Mục 2.2's M-03 adds the
 * real guarded {@code POST /workshops}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthJwtSecurityIT.ProbeConfig.class)
class AuthJwtSecurityIT extends IntegrationTestSupport {

    private static final String PROBE_PATH = "/api/v1/test-only/probe";
    private static final UUID USER = UUID.randomUUID();

    /** A handler that records each invocation: the stand-in for "a business write happened". */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        AtomicInteger probeInvocations() {
            return new AtomicInteger();
        }

        @Bean
        ProbeController probeController(AtomicInteger probeInvocations) {
            return new ProbeController(probeInvocations);
        }
    }

    @RestController
    static class ProbeController {
        private final AtomicInteger invocations;

        ProbeController(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @GetMapping(PROBE_PATH)
        String probe() {
            invocations.incrementAndGet();
            return "reached";
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> SECRET);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AtomicInteger probeInvocations;

    @BeforeEach
    void resetProbe() {
        probeInvocations.set(0);
    }

    private int statusOf(String path, String authorizationHeader) {
        var headers = new HttpHeaders();
        if (authorizationHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value();
    }

    static Stream<Arguments> badTokens() {
        var now = Instant.now();
        var exp = now.plus(Duration.ofMinutes(30));
        var valid = TestJwts.valid(USER, "WORKSHOP_CREATE");
        return Stream.of(
                Arguments.of(
                        "S-1 tampered payload",
                        TestJwts.withPayload(
                                valid,
                                "{\"sub\":\""
                                        + USER
                                        + "\",\"permissions\":[\"REPORT_VIEW\"],\"exp\":"
                                        + exp.getEpochSecond()
                                        + "}")),
                Arguments.of(
                        "S-2 attacker-chosen secret",
                        TestJwts.sign(
                                JWSAlgorithm.HS256,
                                "attacker-chosen-secret-attacker-chosen-secret-attacker-chosen-secret",
                                TestJwts.claims(USER, List.of("WORKSHOP_CREATE"), now, exp))),
                Arguments.of(
                        "S-3 alg none",
                        TestJwts.unsigned(
                                TestJwts.claims(USER, List.of("WORKSHOP_CREATE"), now, exp))),
                Arguments.of("S-4 header claims RS256", TestJwts.withHeaderAlg(valid, "RS256")),
                Arguments.of(
                        "S-4 HS512 with the right secret",
                        TestJwts.sign(
                                JWSAlgorithm.HS512,
                                SECRET,
                                TestJwts.claims(USER, List.of("WORKSHOP_CREATE"), now, exp))),
                Arguments.of("S-5 expired", TestJwts.expired(USER, Duration.ofSeconds(5))),
                Arguments.of("S-6 not a jwt", "not-a-jwt"),
                Arguments.of("S-6 truncated", valid.substring(0, valid.lastIndexOf('.'))));
    }

    @ParameterizedTest(name = "{0} -> 401, handler never reached")
    @MethodSource("badTokens")
    @DisplayName(
            "a protected path answers 401 to every bad bearer token and never runs the handler")
    void protectedPath_rejectsBadToken(String description, String token) {
        assertThat(statusOf(PROBE_PATH, "Bearer " + token)).isEqualTo(401);
        assertThat(probeInvocations).hasValue(0);
    }

    @Test
    @DisplayName("S-6: an empty bearer value is 401 and never runs the handler")
    void protectedPath_rejectsEmptyBearer() {
        assertThat(statusOf(PROBE_PATH, "Bearer ")).isEqualTo(401);
        assertThat(probeInvocations).hasValue(0);
    }

    @Test
    @DisplayName("a request with no Authorization header is 401 and never runs the handler")
    void protectedPath_rejectsMissingToken() {
        assertThat(statusOf(PROBE_PATH, null)).isEqualTo(401);
        assertThat(probeInvocations).hasValue(0);
    }

    @Test
    @DisplayName("a valid token reaches the handler exactly once")
    void protectedPath_acceptsValidToken() {
        var status = statusOf(PROBE_PATH, "Bearer " + TestJwts.valid(USER, "WORKSHOP_CREATE"));

        assertThat(status).isEqualTo(200);
        assertThat(probeInvocations).hasValue(1);
    }

    @Test
    @DisplayName("the public catalog stays reachable without any token")
    void publicCatalog_needsNoToken() {
        assertThat(statusOf("/api/v1/workshops", null)).isEqualTo(200);
    }

    @Test
    @DisplayName(
            "a bad token is rejected even on the public catalog (a presented token is verified)")
    void publicCatalog_withBadToken_isRejected() {
        assertThat(statusOf("/api/v1/workshops", "Bearer not-a-jwt")).isEqualTo(401);
    }
}
