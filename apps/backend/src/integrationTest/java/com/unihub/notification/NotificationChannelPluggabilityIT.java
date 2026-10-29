package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.usecase.NotificationDispatcher;
import com.unihub.shared.infrastructure.Mailpit;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-39 (Phase 4 checklist 5.2, D40: AC40.2, AC40.3, AC40.4): a third notification channel, written
 * and registered here and nowhere in {@code src/main}, receives dispatches next to {@code EMAIL}
 * and {@code IN_APP}; and when it throws, the other two have still sent, in that same delivery.
 *
 * <p>The channel is a {@code @TestConfiguration} bean; which notification types use it is
 * configuration ({@code unihub.notification.channels.*}); the only schema step a real channel takes
 * is a widened {@code channel} CHECK, which here is a test-only migration ({@code
 * db/testmigration}) run, after the production ones, on a <b>database of its own</b> in the
 * singleton Postgres. The shared database keeps exactly the production schema, and the test asserts
 * it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class NotificationChannelPluggabilityIT extends NotificationITBase {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(60);
    private static final String PIGEON = "TEST_PIGEON";
    private static final String OWN_DATABASE =
            "pigeon_" + UUID.randomUUID().toString().replace("-", "");

    static {
        createOwnDatabaseAndMigrate();
    }

    /**
     * The URL of the own database on the singleton container, built, not edited from the shared.
     */
    private static String ownUrl() {
        return "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/"
                + OWN_DATABASE;
    }

    private static void createOwnDatabaseAndMigrate() {
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + OWN_DATABASE);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create the database of its own", e);
        }
        // INV-15 as everywhere in the test suite: Flyway's Java API, never the application.
        // The production migrations, then the test-only location whose versions are far above them.
        Flyway.configure()
                .dataSource(ownUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "classpath:db/testmigration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NotificationChannelPluggabilityIT::ownUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // which types use the third channel is this module's own configuration (D39)
        registry.add(
                "unihub.notification.channels.registration-confirmed",
                () -> "EMAIL,IN_APP," + PIGEON);
        registry.add(
                "unihub.notification.channels.workshop-cancelled", () -> "EMAIL,IN_APP," + PIGEON);
    }

    /** What the test-only channel was asked to send, and whether it fails on cue. */
    static final class TestPigeonChannel implements NotificationChannel {
        final List<NotificationDispatch> dispatches = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public String channelCode() {
            return PIGEON;
        }

        @Override
        public NotificationDeliveryResult send(NotificationDispatch dispatch) {
            dispatches.add(dispatch);
            if (failing) {
                throw new IllegalStateException("the pigeon flew into a window");
            }
            return NotificationDeliveryResult.SENT;
        }
    }

    @TestConfiguration
    static class PigeonConfiguration {
        @Bean
        TestPigeonChannel testPigeonChannel() {
            return new TestPigeonChannel();
        }
    }

    @Autowired private TestPigeonChannel pigeon;
    @Autowired private NotificationDispatcher dispatcher;

    @BeforeEach
    void healthyPigeon() {
        pigeon.dispatches.clear();
        pigeon.failing = false;
    }

    private int rows(UUID workshop, String channel, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE related_entity_id = ? AND channel = ?"
                        + " AND status = ?",
                Integer.class,
                workshop,
                channel,
                status);
    }

    private int retryHop5s() {
        return admin.getQueueInfo("unihub.notification.retry.5s").getMessageCount();
    }

    @Test
    @DisplayName(
            "the test runs on the database of its own, and the shared one is still production's")
    void runsOnItsOwnDatabase_sharedSchemaUntouched() throws SQLException {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(OWN_DATABASE);
        assertThat(dispatcher.channelCodes())
                .as("exactly the two real channels and the test one: no fixture became a bean")
                .containsExactlyInAnyOrder("EMAIL", "IN_APP", PIGEON);
        assertThat(constraintOf(OWN_DATABASE)).contains(PIGEON);
        assertThat(constraintOf(null))
                .as("the shared test database keeps the production CHECK")
                .doesNotContain(PIGEON)
                .contains("EMAIL")
                .contains("IN_APP");
    }

    /** The definition of the channel CHECK in the shared database, or in the named one. */
    private static String constraintOf(String database) throws SQLException {
        var url = database == null ? POSTGRES.getJdbcUrl() : ownUrl();
        try (var connection =
                        DriverManager.getConnection(
                                url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname"
                                        + " = 'notifications_channel_check'")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    @Test
    @DisplayName(
            "AC40.2: the third channel receives the dispatch beside EMAIL and IN_APP, which are"
                    + " unchanged")
    void thirdChannel_receivesTheDispatchNextToTheOthers() {
        var token = "pigeon" + UUID.randomUUID().toString().substring(0, 8);
        var student = student();
        var workshop = aWorkshop("Pigeon " + token);
        var registration = registration(student, workshop, "CONFIRMED");

        publishConfirmed(registration, workshop, student);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(rows(registration, "EMAIL", "SENT")).isEqualTo(1);
                            assertThat(rows(registration, "IN_APP", "SENT")).isEqualTo(1);
                            assertThat(rows(registration, PIGEON, "SENT")).isEqualTo(1);
                        });
        assertThat(notificationRows(student)).as("one row per configured channel").isEqualTo(3);
        assertThat(pigeon.dispatches).hasSize(1);
        var dispatch = pigeon.dispatches.getFirst();
        assertThat(dispatch.userId()).isEqualTo(student);
        assertThat(dispatch.userEmail()).isEqualTo(emailOf(student));
        assertThat(dispatch.type().name()).isEqualTo("REGISTRATION_CONFIRMED");
        assertThat(Mailpit.recipientsOfSubject(token)).containsExactly(emailOf(student));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM notifications WHERE user_id = ? AND channel ="
                                        + " 'IN_APP'",
                                String.class,
                                student))
                .contains(token);
        assertThat(queuedMessages()).isZero();
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName(
            "AC40.3: a channel that throws leaves the other two sent in that same delivery; its own"
                    + " rows stay PENDING and the message goes to the retry chain")
    void aThrowingChannel_doesNotCutTheOthersShort() {
        var recipients = 6;
        var token = "flight" + UUID.randomUUID().toString().substring(0, 8);
        var workshop = aWorkshop("Pigeon " + token);
        var students = IntStream.range(0, recipients).mapToObj(i -> student()).toList();
        students.forEach(s -> registration(s, workshop, "CONFIRMED"));
        pigeon.failing = true;

        publishCancelled(workshop);

        // the first delivery is over when its message is in the first retry stage
        Awaitility.await().atMost(LONG_ENOUGH).until(() -> retryHop5s() == 1);
        assertThat(rows(workshop.value(), "EMAIL", "SENT"))
                .as("every email went out in the delivery the pigeon failed in")
                .isEqualTo(recipients);
        assertThat(rows(workshop.value(), "IN_APP", "SENT")).isEqualTo(recipients);
        assertThat(rows(workshop.value(), PIGEON, "PENDING")).isEqualTo(recipients);
        assertThat(pigeon.dispatches)
                .as("the failing channel's remaining rows were skipped, not tried one by one")
                .hasSize(1);
        assertThat(parkedMessages()).isZero();

        // the pigeon recovers; the next hop sends only what is still PENDING
        pigeon.failing = false;
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(() -> rows(workshop.value(), PIGEON, "SENT") == recipients);
        assertThat(Mailpit.recipientsOfSubject(token))
                .as("the retry did not send an email again")
                .hasSize(recipients)
                .doesNotHaveDuplicates();
        assertThat(rows(workshop.value(), "IN_APP", "SENT")).isEqualTo(recipients);
        assertThat(parkedMessages()).isZero();
    }
}
