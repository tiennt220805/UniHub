package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.shared.infrastructure.ControllableSmtp;
import com.unihub.shared.infrastructure.ControllableSmtp.Mode;
import com.unihub.shared.infrastructure.Mailpit;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Delivery on the real infrastructure, with a provider that fails on cue (Phase 4 checklist 2.8:
 * AC32.3, AC32.5, AC39.3, AC39.4). Postgres, the broker and the SMTP connection are real; only the
 * SMTP endpoint is a {@link ControllableSmtp} in front of Mailpit, and the retry chain is seconds
 * long on a broker of its own (queue arguments cannot change once a queue exists, so the shortened
 * topology cannot go on the broker the other classes share). The real channels are wrapped, not
 * replaced, to see what they are called with and to repair the provider at an exact moment.
 *
 * <p>The send lease is 120 seconds, longer than the whole shortened chain, so a retry can only send
 * if the failed attempt gave its claim back: a release that did nothing would park the message.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class NotificationDeliveryIT extends NotificationITSupport {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(60);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(1500);

    private static final RabbitMQContainer SHORT_BROKER =
            new RabbitMQContainer("rabbitmq:3.13.7-management");
    private static final ControllableSmtp SMTP;

    static {
        SHORT_BROKER.start();
        SMTP = ControllableSmtp.inFrontOfMailpit();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", SHORT_BROKER::getHost);
        registry.add("spring.rabbitmq.port", SHORT_BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", SHORT_BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", SHORT_BROKER::getAdminPassword);
        registry.add("unihub.messaging.retry-delays", () -> "PT0.3S,PT0.3S,PT0.3S");
        registry.add("unihub.notification.smtp.host", SMTP::host);
        registry.add("unihub.notification.smtp.port", () -> String.valueOf(SMTP.port()));
        registry.add("unihub.notification.smtp.connect-timeout", () -> "PT1S");
        registry.add("unihub.notification.smtp.read-timeout", READ_TIMEOUT::toString);
        registry.add("unihub.notification.smtp.write-timeout", READ_TIMEOUT::toString);
    }

    /** One call of a real channel, as the wrapper saw it. */
    record Call(
            String channel,
            boolean transactionOpen,
            NotificationDeliveryResult result,
            long startedAtNanos,
            long tookNanos) {}

    private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    private static volatile BiConsumer<String, NotificationDeliveryResult> afterSend;
    private static final AtomicInteger SENT_EMAILS = new AtomicInteger();

    /** Wraps every {@link NotificationChannel} bean; the real channel still does the sending. */
    @TestConfiguration
    static class Probes {
        @Bean
        static BeanPostProcessor recordingChannels() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name)
                        throws BeansException {
                    if (!(bean instanceof NotificationChannel channel)) {
                        return bean;
                    }
                    return new NotificationChannel() {
                        @Override
                        public String channelCode() {
                            return channel.channelCode();
                        }

                        @Override
                        public NotificationDeliveryResult send(NotificationDispatch dispatch) {
                            var transaction =
                                    TransactionSynchronizationManager.isActualTransactionActive();
                            var started = System.nanoTime();
                            var result = channel.send(dispatch);
                            CALLS.add(
                                    new Call(
                                            channelCode(),
                                            transaction,
                                            result,
                                            started,
                                            System.nanoTime() - started));
                            var hook = afterSend;
                            if (hook != null) {
                                hook.accept(channelCode(), result);
                            }
                            return result;
                        }
                    };
                }
            };
        }
    }

    /**
     * The global registry has no backend before Phase 6, so its meters count nothing until one is
     * added; the same trick as the other modules' metric tests.
     */
    private static final SimpleMeterRegistry METERS = new SimpleMeterRegistry();

    @BeforeAll
    static void collectMetrics() {
        Metrics.addRegistry(METERS);
    }

    @AfterAll
    static void closeTheProvider() {
        Metrics.removeRegistry(METERS);
        SMTP.close();
    }

    @BeforeEach
    void healthyProvider() {
        CALLS.clear();
        afterSend = null;
        SENT_EMAILS.set(0);
        SMTP.mode(Mode.FORWARD);
    }

    private static List<Call> calls(String channel) {
        return CALLS.stream().filter(c -> c.channel().equals(channel)).toList();
    }

    private static double unavailableEmails() {
        var counter =
                METERS.find("unihub.notification.delivery")
                        .tags("channel", "EMAIL", "result", "UNAVAILABLE")
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName(
            "AC32.5: the provider comes back before the second hop, and the email goes in that hop"
                    + " because the failed attempt gave its claim back")
    void providerRecovers_emailGoesOutInTheNextHop() {
        var student = student();
        var workshop = aWorkshop("Recovery workshop");
        var registration = registration(student, workshop, "CONFIRMED");
        SMTP.mode(Mode.CLOSE);
        // repaired at the exact moment the first attempt has failed, before it is even retried
        afterSend =
                (channel, result) -> {
                    if (channel.equals("EMAIL")
                            && result == NotificationDeliveryResult.UNAVAILABLE) {
                        SMTP.mode(Mode.FORWARD);
                    }
                };

        publishConfirmed(registration, workshop, student);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(notificationRows(student, "EMAIL", "SENT")).isEqualTo(1);
                            assertThat(notificationRows(student, "IN_APP", "SENT")).isEqualTo(1);
                        });
        assertThat(Mailpit.to(emailOf(student))).hasSize(1);
        var email = calls("EMAIL");
        assertThat(email)
                .extracting(Call::result)
                .containsExactly(
                        NotificationDeliveryResult.UNAVAILABLE, NotificationDeliveryResult.SENT);
        assertThat(Duration.ofNanos(email.get(1).startedAtNanos() - email.get(0).startedAtNanos()))
                .as("the second attempt is the retry hop, not part of the first delivery")
                .isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(calls("IN_APP")).as("the in-app row was not sent twice").hasSize(1);
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("AC39.3: no transaction is open while a provider is called")
    void noTransactionIsOpenDuringTheProviderCall() {
        var student = student();
        var workshop = aWorkshop("Plain workshop");
        var registration = registration(student, workshop, "CONFIRMED");

        publishConfirmed(registration, workshop, student);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(
                        () ->
                                notificationRows(student, "EMAIL", "SENT") == 1
                                        && notificationRows(student, "IN_APP", "SENT") == 1);
        assertThat(calls("EMAIL")).isNotEmpty();
        assertThat(calls("IN_APP")).isNotEmpty();
        assertThat(CALLS)
                .as("every call of a real provider adapter")
                .allSatisfy(call -> assertThat(call.transactionOpen()).isFalse());
    }

    @Test
    @DisplayName(
            "AC32.3: a provider that stays down past the chain parks the message, the email row"
                    + " stays PENDING, the in-app one is SENT and the counter counts every attempt")
    void providerDownPastTheChain_parksTheMessage() {
        var student = student();
        var workshop = aWorkshop("Outage workshop");
        var registration = registration(student, workshop, "CONFIRMED");
        SMTP.mode(Mode.CLOSE);
        var before = unavailableEmails();

        publishConfirmed(registration, workshop, student);

        Awaitility.await().atMost(LONG_ENOUGH).until(() -> parkedMessages() == 1);
        assertThat(notificationRows(student, "EMAIL", "PENDING")).isEqualTo(1);
        assertThat(notificationRows(student, "IN_APP", "SENT")).isEqualTo(1);
        assertThat(Mailpit.to(emailOf(student))).isEmpty();
        var attempts = calls("EMAIL");
        assertThat(attempts)
                .as("the first delivery and one per retry hop")
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(
                        call ->
                                assertThat(call.result())
                                        .isEqualTo(NotificationDeliveryResult.UNAVAILABLE));
        assertThat(unavailableEmails() - before).isEqualTo(attempts.size());
        assertThat(queuedMessages()).as("nothing left behind on the queue").isZero();
    }

    @Test
    @DisplayName(
            "AC39.4: a hanging provider with N recipients costs one timeout per delivery, not N;"
                    + " every in-app row is SENT and every email row waits")
    void hangingProvider_costsOneTimeoutPerDelivery() {
        var recipients = 10;
        var workshop = aWorkshop("Hanging workshop");
        var students = IntStream.range(0, recipients).mapToObj(i -> student()).toList();
        students.forEach(s -> registration(s, workshop, "CONFIRMED"));
        SMTP.mode(Mode.HANG);

        publishCancelled(workshop);

        Awaitility.await().atMost(LONG_ENOUGH).until(() -> parkedMessages() == 1);
        var email = calls("EMAIL");
        assertThat(email)
                .as("one email attempt per delivery: the rest of the channel is skipped")
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(4)
                .allSatisfy(
                        call -> {
                            assertThat(call.result())
                                    .isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
                            assertThat(Duration.ofNanos(call.tookNanos()))
                                    .as("bounded by the read timeout, not by the provider")
                                    .isLessThan(READ_TIMEOUT.multipliedBy(3));
                        });
        assertThat(calls("IN_APP")).as("each in-app row sent once").hasSize(recipients);
        students.forEach(
                s -> {
                    assertThat(notificationRows(s, "IN_APP", "SENT")).isEqualTo(1);
                    assertThat(notificationRows(s, "EMAIL", "PENDING")).isEqualTo(1);
                });
    }

    @Test
    @DisplayName(
            "AC39.1, AC39.2: a fan-out of 60 whose email channel fails midway still tells everyone"
                    + " in-app at once, and the retry sends only the emails still PENDING")
    void fanOutWithAChannelFailingMidway() {
        fanOut(Set.of(20));
    }

    @Test
    @DisplayName(
            "M-38: the same fan-out with one more, unplanned, email failure before the planned"
                    + " outage (a provider that is slow once) still sends each email exactly once")
    void fanOutWithATransientFailureBeforeTheOutage() {
        fanOut(Set.of(4, 20));
    }

    /**
     * 60 recipients; the provider goes down after each count in {@code outagesAfter} sends and is
     * back as soon as one send has failed.
     */
    private void fanOut(Set<Integer> outagesAfter) {
        var recipients = 60;
        var token = "fanout" + UUID.randomUUID().toString().substring(0, 8);
        var workshop = aWorkshop("Fan-out " + token);
        var students = IntStream.range(0, recipients).mapToObj(i -> student()).toList();
        students.forEach(s -> registration(s, workshop, "CONFIRMED"));
        afterSend =
                (channel, result) -> {
                    if (!channel.equals("EMAIL")) {
                        return;
                    }
                    if (result == NotificationDeliveryResult.SENT
                            && outagesAfter.contains(SENT_EMAILS.incrementAndGet())) {
                        SMTP.mode(Mode.CLOSE);
                    } else if (result == NotificationDeliveryResult.UNAVAILABLE) {
                        SMTP.mode(Mode.FORWARD);
                    }
                };

        publishCancelled(workshop);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM notifications"
                                                                + " WHERE related_entity_id = ? AND"
                                                                + " status = 'SENT'",
                                                        Integer.class,
                                                        workshop.value()))
                                        .isEqualTo(recipients * 2));
        assertThat(Mailpit.recipientsOfSubject(token))
                .as("exactly one email per student: the retry did not repeat what was sent")
                .hasSize(recipients)
                .doesNotHaveDuplicates();
        var email = calls("EMAIL");
        var failed =
                email.stream()
                        .filter(c -> c.result() == NotificationDeliveryResult.UNAVAILABLE)
                        .count();
        // Not "exactly 61 calls": any one send that fails by itself (a provider that is slow once,
        // on a loaded runner) adds a failed call and moves the planned outage to a later hop, which
        // is not what is under test. What is: each email is sent successfully once, and the failing
        // channel does not hold the in-app one back (the ordering check below, which is what a
        // channel that is not skipped within a delivery turns red; the hanging-provider test above
        // is the guard for one timeout per delivery). The bound on failed calls only stops a
        // runaway:
        // the first delivery and its three retry hops.
        assertThat(email.stream().filter(c -> c.result() == NotificationDeliveryResult.SENT))
                .as("every email sent successfully exactly once; calls=" + describe())
                .hasSize(recipients);
        assertThat(failed)
                .as("every planned outage was met; calls=" + describe())
                .isGreaterThanOrEqualTo(outagesAfter.size());
        assertThat(failed)
                .as(
                        "one failed call per delivery (the first and three retry hops), not one per row;"
                                + " calls="
                                + describe())
                .isLessThanOrEqualTo(4);
        var inApp = calls("IN_APP");
        assertThat(inApp)
                .as("each in-app row sent once, the retry never touched one")
                .hasSize(recipients);
        // the failing channel did not hold the other back: every in-app row was sent in the first
        // delivery, before the retry sent the first of the emails that were left
        var firstFailure =
                CALLS.indexOf(
                        email.stream()
                                .filter(c -> c.result() == NotificationDeliveryResult.UNAVAILABLE)
                                .findFirst()
                                .orElseThrow());
        var firstRetriedEmail =
                IntStream.range(firstFailure + 1, CALLS.size())
                        .filter(k -> CALLS.get(k).channel().equals("EMAIL"))
                        .findFirst()
                        .orElseThrow();
        assertThat(CALLS.subList(firstRetriedEmail, CALLS.size()))
                .noneMatch(c -> c.channel().equals("IN_APP"));
        assertThat(parkedMessages()).isZero();
    }

    /** The calls in order, one short token each, for the message of a failed assertion. */
    private static String describe() {
        var out = new StringBuilder();
        CALLS.forEach(
                c ->
                        out.append(c.channel().charAt(0))
                                .append(c.result() == NotificationDeliveryResult.SENT ? '+' : '!'));
        return out.toString();
    }
}
