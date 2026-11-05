package com.unihub.shared.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * INV-17 on one instance (M-61; distributed-coordination.md §3; Phase 7 checklist 3.4): the real
 * filter chain with the real Redis limiter. MockMvc, not a socket, because the address a budget is
 * charged to is the request's {@code remoteAddr}, which only MockMvc can choose — each test uses an
 * address and users of its own (the Redis is shared by the whole run and a budget lasts 60 s).
 * Boundary values are those of {@code test-design-techniques.md} §4: 5 / 6 and 100 / 101.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "unihub.rate-limit.enabled=true")
@AutoConfigureMockMvc
class RateLimitIT extends IntegrationTestSupport {

    private static final String CATALOG = "/api/v1/workshops";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REGISTRATIONS = "/api/v1/registrations";
    private static final String WEBHOOK = "/api/v1/webhooks/payment-gateway";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("management.tracing.sampling.probability", () -> "1.0");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestRestTemplate restTemplate;
    @LocalManagementPort private int managementPort;

    /** A private-range address no other test uses: its own budget. */
    private static String freshAddress() {
        var r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    private static String bearer(UUID user, String... permissions) {
        return "Bearer " + TestJwts.valid(user, permissions);
    }

    private static MockHttpServletRequestBuilder from(
            MockHttpServletRequestBuilder request, String address) {
        return request.with(
                r -> {
                    r.setRemoteAddr(address);
                    return r;
                });
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder registration(UUID user, String address) {
        // an empty body is a 400 before any use case: the attempt costs nothing but the budget
        return from(
                post(REGISTRATIONS)
                        .header("Authorization", bearer(user, "REGISTRATION_CREATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                address);
    }

    @Test
    @DisplayName("S-13 one instance: the 6th registration attempt in 10 s is 429 with Retry-After")
    void registration_fifthIsServed_sixthIsRefused() throws Exception {
        var user = UUID.randomUUID();
        var address = freshAddress();
        for (int i = 1; i <= 5; i++) {
            assertThat(status(registration(user, address))).as("attempt %d", i).isNotEqualTo(429);
        }

        var sixth = perform(registration(user, address)).getResponse();

        assertThat(sixth.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(sixth.getHeader("Retry-After"))).isBetween(1, 10);
        assertThat(sixth.getContentType()).startsWith("application/problem+json");
        var body = json.readTree(sixth.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("correlationId").asText()).matches("[0-9a-f]{32}");
        assertThat(sixth.getHeader("X-Correlation-Id"))
                .isEqualTo(body.get("correlationId").asText());
    }

    @Test
    @DisplayName("the registration budget is per user: another user is not affected")
    void registration_isPerUser() throws Exception {
        var address = freshAddress();
        var spender = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            status(registration(spender, address));
        }

        assertThat(status(registration(spender, address))).isEqualTo(429);
        assertThat(status(registration(UUID.randomUUID(), address))).isNotEqualTo(429);
    }

    @Test
    @DisplayName(
            "an anonymous registration attempt is a 401 and never touches the registration budget")
    void registration_anonymous_is401() throws Exception {
        var address = freshAddress();
        for (int i = 0; i < 8; i++) {
            assertThat(
                            status(
                                    from(
                                            post(REGISTRATIONS)
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content("{}"),
                                            address)))
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("S-14 authenticated: the 101st request in 60 s is 429, charged to the user")
    void general_authenticated_101st() throws Exception {
        var user = UUID.randomUUID();
        var address = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(status(from(get(CATALOG).header("Authorization", bearer(user)), address)))
                    .as("request %d", i)
                    .isEqualTo(200);
        }

        var response =
                perform(from(get(CATALOG).header("Authorization", bearer(user)), address))
                        .getResponse();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(response.getHeader("Retry-After"))).isBetween(1, 60);
    }

    @Test
    @DisplayName("S-14 anonymous: the 101st request from one address in 60 s is 429")
    void general_anonymous_101st() throws Exception {
        var address = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(status(from(get(CATALOG), address))).as("request %d", i).isEqualTo(200);
        }

        assertThat(status(from(get(CATALOG), address))).isEqualTo(429);
    }

    @Test
    @DisplayName("two users from one address do not share a budget, nor with the anonymous one")
    void general_twoUsersOneAddress_doNotShare() throws Exception {
        var address = freshAddress();
        var first = UUID.randomUUID();
        for (int i = 0; i < 101; i++) {
            status(from(get(CATALOG).header("Authorization", bearer(first)), address));
        }

        assertThat(status(from(get(CATALOG).header("Authorization", bearer(first)), address)))
                .isEqualTo(429);
        assertThat(
                        status(
                                from(
                                        get(CATALOG)
                                                .header("Authorization", bearer(UUID.randomUUID())),
                                        address)))
                .isEqualTo(200);
        assertThat(status(from(get(CATALOG), address))).isEqualTo(200);
    }

    @Test
    @DisplayName("S-16 a: rotating X-Forwarded-For never resets the address budget")
    void rotatingForwardedFor_doesNotReset() throws Exception {
        var address = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(
                            status(
                                    from(
                                            get(CATALOG).header("X-Forwarded-For", freshAddress()),
                                            address)))
                    .isEqualTo(200);
        }

        assertThat(status(from(get(CATALOG).header("X-Forwarded-For", freshAddress()), address)))
                .isEqualTo(429);
    }

    @Test
    @DisplayName(
            "S-16 a: a forged X-Envoy-External-Address from any peer but the sidecar is ignored")
    void forgedGatewayHeader_fromAnotherPeer_isIgnored() throws Exception {
        var address = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(
                            status(
                                    from(
                                            get(CATALOG)
                                                    .header(
                                                            "X-Envoy-External-Address",
                                                            freshAddress()),
                                            address)))
                    .isEqualTo(200);
        }

        assertThat(
                        status(
                                from(
                                        get(CATALOG)
                                                .header("X-Envoy-External-Address", freshAddress()),
                                        address)))
                .isEqualTo(429);
    }

    @Test
    @DisplayName(
            "S-16 a: behind the sidecar (peer 127.0.0.6) the gateway's address is the budget's key")
    void behindTheSidecar_gatewayHeaderIsTheKey() throws Exception {
        var client = freshAddress();
        var other = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(
                            status(
                                    from(
                                            get(CATALOG)
                                                    .header("X-Envoy-External-Address", client)
                                                    .header("X-Forwarded-For", freshAddress()),
                                            "127.0.0.6")))
                    .isEqualTo(200);
        }

        // the same client, whatever X-Forwarded-For says: spent
        assertThat(
                        status(
                                from(
                                        get(CATALOG)
                                                .header("X-Envoy-External-Address", client)
                                                .header("X-Forwarded-For", freshAddress()),
                                        "127.0.0.6")))
                .isEqualTo(429);
        // a different client behind the same sidecar has a budget of its own
        assertThat(
                        status(
                                from(
                                        get(CATALOG).header("X-Envoy-External-Address", other),
                                        "127.0.0.6")))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("S-16 b: more than 100 login attempts in 60 s from one address are 429")
    void login_over100_is429() throws Exception {
        var address = freshAddress();
        var credentials = "{\"email\":\"nobody@example.test\",\"password\":\"wrong-password\"}";
        for (int i = 1; i <= 100; i++) {
            assertThat(
                            status(
                                    from(
                                            post(LOGIN)
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(credentials),
                                            address)))
                    .as("attempt %d", i)
                    .isEqualTo(401);
        }

        var response =
                perform(
                                from(
                                        post(LOGIN)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(credentials),
                                        address))
                        .getResponse();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName(
            "S-16 c: more than 100 requests with an invalid bearer token from one address are 429, not 401")
    void invalidToken_over100_is429() throws Exception {
        var address = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(
                            status(
                                    from(
                                            get(CATALOG)
                                                    .header("Authorization", "Bearer not.a.jwt"),
                                            address)))
                    .as("request %d", i)
                    .isEqualTo(401);
        }

        assertThat(status(from(get(CATALOG).header("Authorization", "Bearer not.a.jwt"), address)))
                .isEqualTo(429);
    }

    @Test
    @DisplayName("a request with no token on a protected path is charged once, then 429, not 401")
    void noToken_protectedPath_chargedOnce() throws Exception {
        var address = freshAddress();
        for (int i = 1; i <= 100; i++) {
            assertThat(status(from(get(REGISTRATIONS), address)))
                    .as("request %d", i)
                    .isEqualTo(401);
        }

        assertThat(status(from(get(REGISTRATIONS), address))).isEqualTo(429);
    }

    @Test
    @DisplayName(
            "the payment webhook is never 429, from one address, with or without a bearer header")
    void webhook_isNeverRateLimited() throws Exception {
        var address = freshAddress();
        for (int i = 0; i < 150; i++) {
            var request =
                    from(
                            post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content("{}"),
                            address);
            if (i % 2 == 1) {
                request.header("Authorization", "Bearer junk");
            }
            assertThat(status(request)).as("webhook call %d", i + 1).isNotEqualTo(429);
        }
    }

    @Test
    @DisplayName("the management port is outside the limiter: probes and scrapes are never 429")
    void managementPort_isNeverRateLimited() {
        for (int i = 0; i < 150; i++) {
            var response =
                    restTemplate
                            .getRestTemplate()
                            .getForEntity(
                                    "http://localhost:"
                                            + managementPort
                                            + "/actuator/health/liveness",
                                    String.class);
            assertThat(response.getStatusCode().value()).as("probe %d", i + 1).isEqualTo(200);
        }
    }
}
