package com.unihub.shared.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.MultiInstanceHarness;
import com.unihub.shared.infrastructure.MultiInstanceHarness.Locking;
import com.unihub.shared.security.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * M-61, INV-17 across instances (security-testing.md S-13 and S-14; Phase 7 checklist 3.5): two
 * application instances, one Redis, requests alternating between them. A per-instance limiter would
 * admit the budget twice (10 registration attempts, 200 requests); the shared one admits it once.
 * Real HTTP to each instance's own port, so the budget's address is {@code 127.0.0.1} — the
 * anonymous test clears that key first, the others use users of their own. The limiter is switched
 * on with a command-line argument, which outranks the system property that switches it off for the
 * shared suite.
 */
class RateLimitMultiInstanceIT extends IntegrationTestSupport {

    private static final String CATALOG = "/api/v1/workshops";
    private static final String REGISTRATIONS = "/api/v1/registrations";

    private static MultiInstanceHarness instances;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startTwoInstances() {
        instances =
                MultiInstanceHarness.start(
                        2, Locking.REDISSON, Duration.ZERO, "unihub.rate-limit.enabled=true");
    }

    @AfterAll
    static void stopInstances() {
        instances.close();
    }

    private static HttpRequest.Builder request(int instance, String path) {
        return HttpRequest.newBuilder(
                URI.create("http://localhost:" + instances.port(instance) + path));
    }

    private static int send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static int register(int instance, UUID user) throws Exception {
        return send(
                request(instance, REGISTRATIONS)
                        .header(
                                "Authorization",
                                "Bearer " + TestJwts.valid(user, "REGISTRATION_CREATE"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build());
    }

    @Test
    @DisplayName(
            "S-13: ten registration attempts of one user alternating between two instances: 5 pass, 5 are 429")
    void registrationBudget_isSharedAcrossInstances() throws Exception {
        var user = UUID.randomUUID();
        var statuses = new ArrayList<Integer>();
        for (int i = 0; i < 10; i++) {
            statuses.add(register(i % 2, user));
        }

        // the empty body is a 400 before any use case; what matters is 429 or not
        assertThat(statuses.stream().filter(s -> s != 429).count())
                .as("attempts that got past the limiter: %s", statuses)
                .isEqualTo(5);
        assertThat(statuses.subList(0, 5)).doesNotContain(429);
        assertThat(statuses.subList(5, 10)).containsOnly(429);
    }

    @Test
    @DisplayName(
            "S-14 authenticated: 101 requests of one user alternating between two instances: the 101st is 429")
    void generalBudget_ofAUser_isSharedAcrossInstances() throws Exception {
        var token = "Bearer " + TestJwts.valid(UUID.randomUUID());
        for (int i = 1; i <= 100; i++) {
            assertThat(
                            send(
                                    request((i - 1) % 2, CATALOG)
                                            .header("Authorization", token)
                                            .GET()
                                            .build()))
                    .as("request %d", i)
                    .isEqualTo(200);
        }

        assertThat(send(request(0, CATALOG).header("Authorization", token).GET().build()))
                .isEqualTo(429);
        assertThat(send(request(1, CATALOG).header("Authorization", token).GET().build()))
                .isEqualTo(429);
    }

    @Test
    @DisplayName(
            "S-14 anonymous: 101 requests from one address alternating between two instances: the 101st is 429")
    void generalBudget_ofAnAddress_isSharedAcrossInstances() throws Exception {
        // a clean budget for 127.0.0.1: nothing else in the run is charged to it (the suite has the
        // limiter off), but a repeated run within 60 s would find it spent
        instances
                .bean(0, RedissonClient.class)
                .getKeys()
                .delete("unihub:ratelimit:global-ip:127.0.0.1");
        for (int i = 1; i <= 100; i++) {
            assertThat(send(request((i - 1) % 2, CATALOG).GET().build()))
                    .as("request %d", i)
                    .isEqualTo(200);
        }

        assertThat(send(request(0, CATALOG).GET().build())).isEqualTo(429);
        assertThat(send(request(1, CATALOG).GET().build())).isEqualTo(429);
    }
}
