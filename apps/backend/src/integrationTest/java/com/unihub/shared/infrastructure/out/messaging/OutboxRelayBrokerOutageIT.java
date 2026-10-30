package com.unihub.shared.infrastructure.out.messaging;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.application.port.in.RelayPendingEventsUseCase;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.config.MessagingProperties;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * A broker that is up but does not answer leaves the outbox rows {@code PENDING}, costs the
 * business request nothing, and costs the relay one wait per cycle, not one per row (Phase 4
 * checklist 7.4, D42, AC42.3, AC42.5, M-41, INV-10). Then, when it answers again, every row goes
 * out.
 *
 * <p>The broker is a RabbitMQ of this class's own, frozen and thawed with Docker's {@code pause}:
 * the process stays, its sockets stay open, nothing is answered, which is a hung broker and not a
 * dead one (a stopped container is the on-demand scenario of {@code resilience-testing.md}, out of
 * the push gate). Because the container is only paused, never restarted, its host port does not
 * change and no fixed port is needed. The singleton broker of the other classes is not touched.
 *
 * <p>Every cycle of the relay is timed, not just the first: a later cycle could wait somewhere
 * other than the confirm (opening a connection, for one) and the first cycle's time would say
 * nothing about it. With twelve rows, one wait each would take a minute and one wait per cycle
 * takes five; the thresholds are read from the bound configuration (the confirm timeout and {@code
 * spring.rabbitmq.channel-rpc-timeout}), so the production values are what is under test. The first
 * cycle after the freeze ends at the confirm timeout; the later ones need a new channel, and that
 * wait is the RPC timeout: left at the client's default of ten minutes it outlasts the lease and a
 * second relay starts alongside.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OutboxRelayBrokerOutageIT.Timed.class)
class OutboxRelayBrokerOutageIT extends IntegrationTestSupport {

    private static final int ROWS = 12;
    private static final String QUEUE = "unihub.notification.queue";

    private static final RabbitMQContainer BROKER =
            new RabbitMQContainer("rabbitmq:3.13.7-management");

    static {
        BROKER.start();
    }

    /** One run of the relay use case: how long it took and how many rows it published. */
    record Cycle(long startedAt, long millis, int published, Throwable failure) {}

    static final List<Cycle> CYCLES = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", BROKER::getHost);
        registry.add("spring.rabbitmq.port", BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", BROKER::getAdminPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    /** Times every run of the relay use case, as the scheduler calls it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Timed {
        @Bean
        static BeanPostProcessor timeTheRelay() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof RelayPendingEventsUseCase real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            RelayPendingEventsUseCase.class.getClassLoader(),
                            new Class<?>[] {RelayPendingEventsUseCase.class},
                            (proxy, method, args) -> {
                                var startedAt = System.currentTimeMillis();
                                var start = System.nanoTime();
                                try {
                                    var published = (int) method.invoke(real, args);
                                    CYCLES.add(
                                            new Cycle(
                                                    startedAt,
                                                    millisSince(start),
                                                    published,
                                                    null));
                                    return published;
                                } catch (InvocationTargetException e) {
                                    CYCLES.add(
                                            new Cycle(
                                                    startedAt,
                                                    millisSince(start),
                                                    0,
                                                    e.getTargetException()));
                                    throw e.getTargetException();
                                }
                            });
                }
            };
        }

        private static long millisSince(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    @Autowired private RabbitProperties rabbitProperties;
    @Autowired private MessagingProperties messagingProperties;
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin admin;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        thaw();
        fixtures = new RegistrationFixtures(jdbc);
        jdbc.update("DELETE FROM event_publications");
        // The application has a live connection to the broker before it goes quiet, as it would
        // in production: opening one against a frozen broker is a different wait.
        admin.initialize();
        admin.purgeQueue(QUEUE);
        rabbit.execute(channel -> channel.isOpen());
        CYCLES.clear();
    }

    @AfterEach
    void tearDown() {
        thaw();
        admin.purgeQueue(QUEUE);
        jdbc.update("DELETE FROM event_publications");
        fixtures.cleanUp();
    }

    private static void freeze() {
        DockerClientFactory.lazyClient().pauseContainerCmd(BROKER.getContainerId()).exec();
    }

    /** Safe to call when the broker is not paused. */
    private static void thaw() {
        try {
            DockerClientFactory.lazyClient().unpauseContainerCmd(BROKER.getContainerId()).exec();
        } catch (RuntimeException notPaused) {
            // the broker was running already
        }
    }

    private WorkshopId aWorkshop() {
        var workshop = aPublishedWorkshop().withTitle("Outage " + UUID.randomUUID()).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    /** A student registers over real HTTP; returns the registration id. */
    private UUID register() throws Exception {
        var student = fixtures.aStudent().value();
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student, "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{\"workshopId\":\"" + aWorkshop().value() + "\"}", headers),
                        String.class);
        assertThat(response.getStatusCode().value())
                .as("the business request does not depend on the broker")
                .isEqualTo(201);
        return UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getBody())
                        .get("id")
                        .asText());
    }

    private int rows(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE status = ?", Integer.class, status);
    }

    @Test
    @DisplayName(
            "AC42.3, AC42.5: a frozen broker leaves the rows PENDING at one timeout per cycle, and"
                    + " every row goes out when it thaws")
    void frozenBroker_thenThawed() throws Exception {
        freeze();
        // Cycles that start from here on are the ones judged. One that finds the outbox still empty
        // ends at once whatever the broker does, so the judgement below also requires that some
        // cycle really waited.
        var judgedFrom = System.currentTimeMillis();
        // Twelve rows appear at once, so that the first cycle after the freeze finds them all, on
        // the channel it already holds: the confirm is the first thing to time out.
        jdbc.update(
                "INSERT INTO event_publications (aggregate_type, aggregate_id, event_type,"
                        + " routing_key, payload) SELECT 'REGISTRATION', gen_random_uuid(),"
                        + " 'TicketConfirmed', 'unihub.registration.ticket.confirmed', '{}'::jsonb"
                        + " FROM generate_series(1, ?)",
                ROWS);

        // meanwhile the business goes on: real requests, answered in a fraction of a wait
        long slowest = 0;
        for (int i = 0; i < 2; i++) {
            var started = System.nanoTime();
            register();
            slowest = Math.max(slowest, (System.nanoTime() - started) / 1_000_000);
        }
        assertThat(slowest)
                .as("no request waited for the broker (ms)")
                .isLessThan(messagingProperties.confirmTimeout().toMillis());
        assertThat(rows("PENDING")).as("the rows are all waiting").isEqualTo(ROWS + 2);

        // let the relay meet the frozen broker several times, whole cycles only
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> CYCLES.stream().filter(c -> c.startedAt() >= judgedFrom).count() >= 4);
        assertThat(rows("PENDING")).as("nothing got through a frozen broker").isEqualTo(ROWS + 2);
        assertThat(rows("PUBLISHED")).isZero();

        var longestWait =
                messagingProperties
                                        .confirmTimeout()
                                        .compareTo(rabbitProperties.getChannelRpcTimeout())
                                > 0
                        ? messagingProperties.confirmTimeout()
                        : rabbitProperties.getChannelRpcTimeout();
        var duringTheOutage = CYCLES.stream().filter(c -> c.startedAt() >= judgedFrom).toList();
        assertThat(duringTheOutage)
                .as("some cycle really met the frozen broker and waited for it")
                .anySatisfy(
                        cycle ->
                                assertThat(cycle.millis())
                                        .isGreaterThanOrEqualTo(
                                                messagingProperties.confirmTimeout().toMillis()
                                                        * 9
                                                        / 10));
        assertThat(duringTheOutage)
                .as("each cycle waited once, then ended")
                .allSatisfy(
                        cycle -> {
                            assertThat(cycle.failure()).isNull();
                            assertThat(cycle.published()).isZero();
                            assertThat(cycle.millis())
                                    .as("cycle length (ms): one wait, not one per row")
                                    .isLessThan(longestWait.toMillis() * 3 / 2);
                        });
        assertThat(duringTheOutage.stream().mapToLong(Cycle::millis).max().orElseThrow())
                .as("far inside the relay's 30 s lease")
                .isLessThan(EventRelayLease.seconds() * 1_000);

        thaw();

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(rows("PENDING")).isZero());
        assertThat(rows("PUBLISHED")).isEqualTo(ROWS + 2);

        // every row reached the queue (a publish whose confirm timed out may also have been
        // delivered once the broker thawed: at-least-once, never fewer)
        var delivered = new HashSet<String>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .until(
                        () -> {
                            var message = rabbit.receive(QUEUE, 200);
                            while (message != null) {
                                delivered.add(message.getMessageProperties().getMessageId());
                                message = rabbit.receive(QUEUE, 200);
                            }
                            return delivered.size() >= ROWS + 2;
                        });
        var expected =
                new HashSet<>(
                        jdbc.queryForList("SELECT id::text FROM event_publications", String.class));
        assertThat(delivered).containsExactlyInAnyOrderElementsOf(expected);
    }

    /** The relay lease the cycle must stay inside (messaging.md §5). */
    private static final class EventRelayLease {
        static long seconds() {
            return 30;
        }
    }
}
