package com.unihub.shared.infrastructure.management;

import static com.unihub.shared.security.TestJwts.SECRET;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-57's application half (Phase 6 checklist 4.4, D76, D77): one real scrape of the management port
 * carries every series of the catalog (observability.md §3, §4), the ones an {@code increase()}
 * alert watches exist at 0 before anything has failed, and the database-backed gauges follow the
 * database. The Prometheus registry is the one Boot builds, with the meters of Phases 2–5 that sit
 * on {@code Metrics.globalRegistry}; the checks on those are for presence only, because the global
 * registry is shared by every Spring context of the test JVM and carries their counts.
 *
 * <p>The context keeps the background refresh on (integration tests switch it off), so it is closed
 * when the class ends instead of staying cached to read from the shared services for the rest of
 * the run.
 *
 * <p>The outbox relay is off in this class, so a {@code PENDING} row planted for the age gauge is
 * not published before the gauge has read it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "unihub.jobs.outbox-relay.enabled=false",
            "unihub.observability.background-refresh=true"
        })
@AutoConfigureObservability
class MetricsExportIT extends IntegrationTestSupport {
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> SECRET);
        registry.add("unihub.redis.address", IntegrationTestSupport::redisAddress);
    }

    @LocalManagementPort private int managementPort;
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;

    /**
     * A real request first: the HTTP timer is born with the first request, the Hikari pool with the
     * first connection.
     */
    private String scrape() {
        http.getForEntity("/api/v1/workshops", String.class);
        jdbc.queryForObject("SELECT 1", Integer.class);
        return http.getForEntity(
                        "http://localhost:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();
    }

    /** The value of the first series whose name and labels start with {@code series}. */
    private static double sample(String body, String series) {
        var pattern =
                series.contains("{")
                        ? Pattern.quote(series.substring(0, series.length() - 1)) + "(?:,[^}]*)?\\}"
                        : Pattern.quote(series) + "(?:\\{[^}]*\\})?";
        var matcher =
                Pattern.compile("^" + pattern + "\\s+(\\S+)$", Pattern.MULTILINE).matcher(body);
        assertThat(matcher.find()).as("series %s in the scrape", series).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private double gauge(String series) {
        return sample(scrape(), series);
    }

    @Test
    @DisplayName("one real scrape carries every application series of the catalog (§3, §4)")
    void everyCatalogSeriesIsInOneScrape() {
        var body = scrape();

        var missing =
                ExpectedMetrics.all().stream().filter(series -> !hasSeries(body, series)).toList();

        assertThat(missing).as("catalog series missing from /actuator/prometheus").isEmpty();
    }

    private static boolean hasSeries(String body, String series) {
        var prefix =
                series.endsWith("}") && series.contains("{")
                        ? series.substring(0, series.length() - 1)
                        : series;
        return Arrays.stream(body.split("\n"))
                .anyMatch(
                        line ->
                                line.startsWith(prefix)
                                        && (prefix.contains("{")
                                                || line.charAt(prefix.length()) == ' '
                                                || line.charAt(prefix.length()) == '{'));
    }

    @Test
    @DisplayName(
            "the audit failure series exist at 0 before any failure, so increase() sees the first")
    void auditFailureSeriesStartAtZero() {
        var body = scrape();

        for (var reason : new String[] {"dropped", "nack", "timeout", "error"}) {
            assertThat(
                            sample(
                                    body,
                                    "unihub_audit_publish_failures_total{reason=\""
                                            + reason
                                            + "\"}"))
                    .as(reason)
                    .isZero();
        }
        // the other counters behind increase() rules live on the global registry: present, and
        // their count belongs to whichever test ran before
        for (var series : ExpectedMetrics.ALERTED_COUNTERS) {
            assertThat(hasSeries(body, series)).as(series).isTrue();
        }
    }

    @Test
    @DisplayName(
            "replica lag is NaN on an instance that is not a replica, never a false 0 (AC76.2)")
    void replicaLagIsUnknownOnAPrimary() {
        await().atMost(PATIENCE)
                .untilAsserted(
                        () ->
                                assertThat(
                                                hasSeries(
                                                        scrape(),
                                                        "unihub_postgres_replica_lag_seconds"))
                                        .isTrue());

        assertThat(gauge("unihub_postgres_replica_lag_seconds")).isNaN();
    }

    @Test
    @DisplayName("the quarantine ratio exists before the first nightly run and says unknown")
    void quarantineRatioIsUnknownBeforeTheFirstRun() {
        assertThat(gauge("unihub_legacy_sync_last_run_quarantine_ratio")).isNaN();
    }

    @Test
    @DisplayName("Redis and RabbitMQ answer the background probe: both dependencies are up")
    void dependenciesAreUp() {
        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            var body = scrape();
                            assertThat(sample(body, "unihub_dependency_up{dependency=\"redis\"}"))
                                    .isEqualTo(1.0);
                            assertThat(
                                            sample(
                                                    body,
                                                    "unihub_dependency_up{dependency=\"rabbitmq\"}"))
                                    .isEqualTo(1.0);
                        });
    }

    @Test
    @DisplayName("the oldest PENDING outbox row sets the age gauge, and it is 0 with none waiting")
    void outboxAgeFollowsTheOldestPendingRow() {
        await().atMost(PATIENCE)
                .untilAsserted(
                        () ->
                                assertThat(gauge("unihub_outbox_oldest_pending_age_seconds"))
                                        .isZero());

        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO event_publications (id, aggregate_type, aggregate_id, event_type,"
                        + " routing_key, payload, created_at) VALUES (?, 'PAYMENT', ?, 'Sample', 'k',"
                        + " '{}'::jsonb, now() - interval '5 minutes')",
                id,
                UUID.randomUUID());
        try {
            await().atMost(PATIENCE)
                    .untilAsserted(
                            () ->
                                    assertThat(gauge("unihub_outbox_oldest_pending_age_seconds"))
                                            .isBetween(300.0, 360.0));
        } finally {
            jdbc.update("DELETE FROM event_publications WHERE id = ?", id);
        }
        await().atMost(PATIENCE)
                .untilAsserted(
                        () ->
                                assertThat(gauge("unihub_outbox_oldest_pending_age_seconds"))
                                        .isZero());
    }

    @Test
    @DisplayName("an open hold is counted by the active-holds gauge until it is released")
    void activeHoldsCountReservedRegistrations() {
        var fixtures = new RegistrationFixtures(jdbc);
        try {
            await().atMost(PATIENCE)
                    .untilAsserted(
                            () -> assertThat(gauge("unihub_registration_active_holds")).isZero());

            var workshop = aPublishedWorkshop().withCapacity(20).build();
            tx.executeWithoutResult(status -> workshops.save(workshop));
            fixtures.track(workshop.id());
            var student = fixtures.aStudent();
            jdbc.update(
                    "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status,"
                            + " hold_expires_at) VALUES (?, ?, ?, 'PAID', 'RESERVED',"
                            + " now() + interval '10 minutes')",
                    UUID.randomUUID(),
                    workshop.id().value(),
                    student.value());

            await().atMost(PATIENCE)
                    .untilAsserted(
                            () ->
                                    assertThat(gauge("unihub_registration_active_holds"))
                                            .isEqualTo(1.0));
        } finally {
            fixtures.cleanUp();
        }
    }

    @Test
    @DisplayName("the API port does not serve /actuator/prometheus")
    void apiPortDoesNotServePrometheus() {
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode().value())
                .isIn(401, 404);
    }
}
