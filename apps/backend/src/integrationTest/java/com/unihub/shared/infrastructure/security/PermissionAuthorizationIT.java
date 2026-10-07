package com.unihub.shared.infrastructure.security;

import static com.unihub.shared.infrastructure.security.SecurityProbeConfig.GUARDED_PATH;
import static com.unihub.shared.security.TestJwts.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-03 (INV-01): an endpoint is open to a caller iff the token's {@code permissions[]} contains the
 * permission it names — never because of a role name — and a missing/bad token (401) is never
 * confused with a good token lacking the permission (403). Every refusal is an RFC 7807 {@code
 * problem+json} and never reaches the handler (auth-and-identity.md §3/§5; security-testing.md
 * S-7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityProbeConfig.class)
class PermissionAuthorizationIT extends IntegrationTestSupport {

    private static final UUID USER = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

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

    private ResponseEntity<String> get(String bearerToken) {
        var headers = new HttpHeaders();
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange(
                GUARDED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void assertProblem(ResponseEntity<String> response, int status, String title)
            throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        t ->
                                assertThat(t.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                                        .isTrue());
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("title").asText()).isEqualTo(title);
        assertThat(body.get("instance").asText()).isEqualTo(GUARDED_PATH);
        assertThat(probeInvocations).hasValue(0);
    }

    @Test
    @DisplayName("a token carrying the required permission reaches the handler")
    void permissionPresent_reachesHandler() {
        var response = get(TestJwts.valid(USER, "WORKSHOP_CREATE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(probeInvocations).hasValue(1);
    }

    @Test
    @DisplayName("a token with several permissions works when one of them is the required one")
    void permissionAmongOthers_reachesHandler() {
        var response = get(TestJwts.valid(USER, "REPORT_VIEW", "WORKSHOP_CREATE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a good token lacking the permission is 403 problem+json, handler not reached")
    void permissionMissing_is403() throws Exception {
        assertProblem(get(TestJwts.valid(USER, "WORKSHOP_EDIT", "REPORT_VIEW")), 403, "Forbidden");
    }

    @Test
    @DisplayName("a good token with an empty permission list is 403")
    void noPermissions_is403() throws Exception {
        assertProblem(get(TestJwts.valid(USER)), 403, "Forbidden");
    }

    @Test
    @DisplayName("S-7: a correctly signed token claiming role ORGANIZER but no permissions is 403")
    void roleClaim_isNotHonored() throws Exception {
        var token = TestJwts.withClaims(USER, Map.of("role", "ORGANIZER"));

        assertProblem(get(token), 403, "Forbidden");
    }

    @Test
    @DisplayName("a scope claim naming the permission grants nothing: only permissions[] counts")
    void scopeClaim_isNotHonored() throws Exception {
        var token = TestJwts.withClaims(USER, Map.of("scope", "WORKSHOP_CREATE"));

        assertProblem(get(token), 403, "Forbidden");
    }

    @Test
    @DisplayName("permission codes match exactly: a different case is a different permission")
    void permissionCode_isCaseSensitive() throws Exception {
        assertProblem(get(TestJwts.valid(USER, "workshop_create")), 403, "Forbidden");
    }

    @Test
    @DisplayName("no token is 401 problem+json with a Bearer challenge, handler not reached")
    void noToken_is401() throws Exception {
        var response = get(null);

        assertProblem(response, 401, "Unauthorized");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer");
    }

    @Test
    @DisplayName("an invalid token is 401, not 403, and its body does not leak the decoder's error")
    void invalidToken_is401_withoutInternalDetail() throws Exception {
        var response = get("not-a-jwt");

        assertProblem(response, 401, "Unauthorized");
        assertThat(response.getBody()).doesNotContainIgnoringCase("jwt").doesNotContain("Nimbus");
    }

    @Test
    @DisplayName("an expired token with the right permission is still 401")
    void expiredToken_is401() throws Exception {
        var expired = TestJwts.expired(USER, java.time.Duration.ofSeconds(5), "WORKSHOP_CREATE");

        assertProblem(get(expired), 401, "Unauthorized");
    }
}
