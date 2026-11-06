package com.unihub.shared.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S-16 (a) as the cluster found it (Phase 7 checklist 8.3, M-62, INV-17): on Kubernetes Spring Boot
 * switches {@code server.forward-headers-strategy} to {@code native} by itself (the platform is
 * detected from {@code KUBERNETES_SERVICE_HOST}), so Tomcat's {@code RemoteIpValve} replaces the
 * request's remote address with the last non-private address of the client-written {@code
 * X-Forwarded-For} — before {@link com.unihub.shared.infrastructure.security.ClientAddress} reads
 * it, and every request then has a budget of its own. {@code RateLimitIT} cannot see this: MockMvc
 * has no Tomcat, so no valve. This test starts the real server, forces the platform to {@code
 * kubernetes} the way the cluster's environment does, and sends a real socket's requests, each with
 * a different {@code X-Forwarded-For}: the budget (100 per 60 s) must still refuse some.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"unihub.rate-limit.enabled=true", "spring.main.cloud-platform=kubernetes"})
class RateLimitForwardedHeadersIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @LocalServerPort private int port;

    @Test
    @DisplayName(
            "on the Kubernetes platform, 130 requests from one socket with a different"
                    + " X-Forwarded-For each are still stopped by the address budget")
    void rotatingForwardedFor_overARealSocket_doesNotReset() throws Exception {
        var client = HttpClient.newHttpClient();
        var refused = 0;
        var served = 0;
        for (int i = 0; i < 130; i++) {
            var r = ThreadLocalRandom.current();
            var forged = "198.51." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/workshops"))
                                    .header("X-Forwarded-For", forged)
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 429) {
                refused++;
            } else if (response.statusCode() == 200) {
                served++;
            }
        }

        assertThat(refused)
                .as("429s among 130 requests: a client-written X-Forwarded-For chose the budget")
                .isGreaterThanOrEqualTo(25);
        assertThat(served).as("requests served before the budget ran out").isLessThanOrEqualTo(105);
    }
}
