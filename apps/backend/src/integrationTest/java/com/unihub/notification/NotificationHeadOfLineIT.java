package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.ControllableSmtp;
import com.unihub.shared.infrastructure.ControllableSmtp.Mode;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A confirmation does not wait behind a large fan-out (Phase 4 checklist 2.9, D39, AC39.6): the
 * listener container takes two messages at a time and one message per consumer, so a delivery that
 * is busy sending forty emails through a slow provider does not hold the next message in line.
 * {@code NotificationListenerContainerIT} shows the configuration; this shows what it does.
 *
 * <p>Three messages are published while the first is being sent: a second, small fan-out and then
 * the confirmation. With a prefetch of one the confirmation is delivered to whichever consumer
 * finishes first, which is the one on the small fan-out; a larger prefetch would already have
 * handed it to the consumer busy with the large one. With a single consumer it would wait behind
 * both. The proof is read in the safe direction: once the confirmation's email is {@code SENT}, the
 * large fan-out still has emails {@code PENDING}.
 *
 * <p>The provider is {@link ControllableSmtp} in {@link Mode#SLOW}: it answers, after a pause per
 * connection, which is slow without being down.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class NotificationHeadOfLineIT extends NotificationITSupport {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(90);
    private static final int LARGE = 40;
    private static final int SMALL = 3;

    private static final ControllableSmtp SMTP;

    static {
        // IntegrationTestSupport has started Mailpit by the time this class is loaded
        SMTP = ControllableSmtp.inFrontOfMailpit();
        SMTP.slowBy(Duration.ofMillis(300));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("unihub.notification.smtp.host", SMTP::host);
        registry.add("unihub.notification.smtp.port", () -> String.valueOf(SMTP.port()));
    }

    @AfterAll
    static void closeTheProvider() {
        SMTP.close();
    }

    @BeforeEach
    void slowProvider() {
        SMTP.mode(Mode.SLOW);
    }

    private int pendingEmails(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE related_entity_id = ? AND channel ="
                        + " 'EMAIL' AND status = 'PENDING'",
                Integer.class,
                workshop);
    }

    private int sentEmails(UUID workshop) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE related_entity_id = ? AND channel ="
                        + " 'EMAIL' AND status = 'SENT'",
                Integer.class,
                workshop);
    }

    @Test
    @DisplayName("AC39.6: a confirmation is sent while a large fan-out is still being delivered")
    void confirmation_doesNotWaitBehindALargeFanOut() {
        var large = aWorkshop("Large fan-out");
        IntStream.range(0, LARGE).forEach(i -> registration(student(), large, "CONFIRMED"));
        var small = aWorkshop("Small fan-out");
        IntStream.range(0, SMALL).forEach(i -> registration(student(), small, "CONFIRMED"));
        var confirmedWorkshop = aWorkshop("Confirmation");
        var newcomer = student();
        var registration = registration(newcomer, confirmedWorkshop, "CONFIRMED");

        publishCancelled(large);
        // the large fan-out is genuinely under way before the others arrive
        Awaitility.await().atMost(LONG_ENOUGH).until(() -> sentEmails(large.value()) >= 1);
        publishCancelled(small);
        publishConfirmed(registration, confirmedWorkshop, newcomer);

        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(() -> notificationRows(newcomer, "EMAIL", "SENT") == 1);
        // read after the confirmation went out, never before
        assertThat(pendingEmails(large.value()))
                .as("the confirmation was sent while the large fan-out was still in progress")
                .isPositive();

        // and nothing was lost on the way
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(
                        () ->
                                sentEmails(large.value()) == LARGE
                                        && sentEmails(small.value()) == SMALL);
        assertThat(parkedMessages()).isZero();
    }
}
