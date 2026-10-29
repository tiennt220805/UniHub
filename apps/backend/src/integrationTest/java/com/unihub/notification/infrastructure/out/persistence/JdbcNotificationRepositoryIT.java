package com.unihub.notification.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The database semantics the consumer's idempotency rests on (INV-11; Phase 4 checklist D31, D33),
 * against real Postgres: the unique key absorbs a redelivered insert, the claim lets exactly one of
 * two concurrent deliveries send a row, a lease that ran out can be taken again, and a row only
 * ever moves {@code PENDING → SENT | FAILED}.
 */
@SpringBootTest
class JdbcNotificationRepositoryIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private NotificationRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        users.forEach(
                user -> {
                    jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
                    jdbc.update("DELETE FROM users WHERE id = ?", user);
                });
    }

    private UUID user() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, 'Notified')",
                id,
                id + "@example.test");
        users.add(id);
        return id;
    }

    private static NewNotification row(UUID event, UUID user, String channel) {
        return new NewNotification(
                event,
                user,
                channel,
                "WORKSHOP_CANCELLED",
                "WORKSHOP",
                UUID.randomUUID(),
                channel.equals("IN_APP") ? "Title" : null,
                channel.equals("IN_APP") ? "Body" : null);
    }

    private UUID onlyRowOf(UUID user, String channel) {
        return jdbc.queryForObject(
                "SELECT id FROM notifications WHERE user_id = ? AND channel = ?",
                UUID.class,
                user,
                channel);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName(
            "an insert delivered twice leaves one row per (event, user, channel), content kept (AC31.1)")
    void insertIfAbsent_absorbsARedelivery() {
        var event = UUID.randomUUID();
        var ann = user();
        var rows = List.of(row(event, ann, "EMAIL"), row(event, ann, "IN_APP"));

        assertThat(repository.insertIfAbsent(rows)).isEqualTo(2);
        assertThat(repository.insertIfAbsent(rows)).isZero();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                ann))
                .isEqualTo(2);
        var inApp =
                jdbc.queryForMap(
                        "SELECT title, body, status FROM notifications WHERE user_id = ? AND channel = 'IN_APP'",
                        ann);
        assertThat(inApp)
                .containsEntry("title", "Title")
                .containsEntry("body", "Body")
                .containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("one event fans out to many recipients and channels without a conflict (AC31.4)")
    void insertIfAbsent_fansOut() {
        var event = UUID.randomUUID();
        var recipients = List.of(user(), user(), user());
        var rows = new ArrayList<NewNotification>();
        recipients.forEach(
                u -> {
                    rows.add(row(event, u, "EMAIL"));
                    rows.add(row(event, u, "IN_APP"));
                });

        assertThat(repository.insertIfAbsent(rows)).isEqualTo(6);
        assertThat(repository.countPending(event)).isEqualTo(6);
        assertThat(repository.insertIfAbsent(List.of())).isZero();
    }

    @Test
    @DisplayName("two concurrent inserts of the same event leave one row per key (AC31.2)")
    void insertIfAbsent_concurrently() throws Exception {
        var event = UUID.randomUUID();
        var ann = user();
        var rows = List.of(row(event, ann, "EMAIL"), row(event, ann, "IN_APP"));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return repository.insertIfAbsent(rows);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return repository.insertIfAbsent(rows);
                            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        assertThat(repository.countPending(event)).isEqualTo(2);
    }

    @Test
    @DisplayName("findPending returns the event's PENDING rows in a fixed order, and only those")
    void findPending_isStableAndOnlyPending() {
        var event = UUID.randomUUID();
        var recipients = List.of(user(), user(), user(), user());
        repository.insertIfAbsent(recipients.stream().map(u -> row(event, u, "EMAIL")).toList());
        repository.markSent(onlyRowOf(recipients.get(0), "EMAIL"));
        repository.markFailed(onlyRowOf(recipients.get(1), "EMAIL"));
        repository.insertIfAbsent(List.of(row(UUID.randomUUID(), user(), "EMAIL")));

        var first = repository.findPending(event);
        var second = repository.findPending(event);

        assertThat(first)
                .extracting(p -> p.userId())
                .containsExactlyInAnyOrder(recipients.get(2), recipients.get(3));
        assertThat(second).isEqualTo(first);
        assertThat(first).extracting(p -> p.id().toString()).isSorted();
        assertThat(first.getFirst().channel()).isEqualTo("EMAIL");
        assertThat(first.getFirst().type()).isEqualTo("WORKSHOP_CANCELLED");
    }

    @Test
    @DisplayName("of two concurrent claims on one row exactly one wins (AC33.4)")
    void claim_exactlyOneWinsTheRace() throws Exception {
        var ann = user();
        repository.insertIfAbsent(List.of(row(UUID.randomUUID(), ann, "EMAIL")));
        var id = onlyRowOf(ann, "EMAIL");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var i = 0; i < 8; i++) {
                attempts.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return repository.claim(id, Duration.ofMinutes(2)).isPresent();
                                }));
            }
            start.countDown();
            var winners = 0;
            for (var attempt : attempts) {
                winners += attempt.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a claim whose lease ran out can be taken again; a live one cannot (AC33.5)")
    void claim_expiredLeaseIsTakenOver() throws Exception {
        var ann = user();
        repository.insertIfAbsent(List.of(row(UUID.randomUUID(), ann, "EMAIL")));
        var id = onlyRowOf(ann, "EMAIL");
        var lease = Duration.ofMillis(300);

        var crashedHolder = repository.claim(id, lease);
        assertThat(crashedHolder).isPresent();
        assertThat(repository.claim(id, lease)).as("lease still live").isEmpty();
        Thread.sleep(400);
        var successor = repository.claim(id, lease);

        assertThat(successor).isPresent();
        assertThat(successor.get()).isAfter(crashedHolder.get());
    }

    @Test
    @DisplayName(
            "release with the claim's own token frees the row at once; with another token it does nothing")
    void release_isByToken() {
        var ann = user();
        repository.insertIfAbsent(List.of(row(UUID.randomUUID(), ann, "EMAIL")));
        var id = onlyRowOf(ann, "EMAIL");
        var token = repository.claim(id, Duration.ofMinutes(2)).orElseThrow();

        repository.release(id, token.minusSeconds(5));
        assertThat(repository.claim(id, Duration.ofMinutes(2)))
                .as("a stale token released nothing")
                .isEmpty();

        repository.release(id, token);
        assertThat(repository.claim(id, Duration.ofMinutes(2)))
                .as("released, claimable at once")
                .isPresent();
    }

    @Test
    @DisplayName("an overrunning sender cannot release a claim another delivery has since taken")
    void release_cannotClearAnotherDeliverysClaim() throws Exception {
        var ann = user();
        repository.insertIfAbsent(List.of(row(UUID.randomUUID(), ann, "EMAIL")));
        var id = onlyRowOf(ann, "EMAIL");
        var lease = Duration.ofMillis(200);
        var slow = repository.claim(id, lease).orElseThrow();
        Thread.sleep(300);
        var successor = repository.claim(id, lease).orElseThrow();

        repository.release(id, slow);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT claimed_at IS NOT NULL FROM notifications WHERE id = ?",
                                Boolean.class,
                                id))
                .isTrue();
        repository.release(id, successor);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT claimed_at IS NULL FROM notifications WHERE id = ?",
                                Boolean.class,
                                id))
                .isTrue();
    }

    @Test
    @DisplayName("a row moves PENDING → SENT | FAILED and never back or across (AC33.2)")
    void statusOnlyMovesForward() {
        var ann = user();
        var bob = user();
        repository.insertIfAbsent(
                List.of(
                        row(UUID.randomUUID(), ann, "EMAIL"),
                        row(UUID.randomUUID(), bob, "EMAIL")));
        var sent = onlyRowOf(ann, "EMAIL");
        var failed = onlyRowOf(bob, "EMAIL");

        repository.markSent(sent);
        repository.markFailed(sent);
        repository.markFailed(failed);
        repository.markSent(failed);

        assertThat(statusOf(sent)).isEqualTo("SENT");
        assertThat(statusOf(failed)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sent_at IS NOT NULL FROM notifications WHERE id = ?",
                                Boolean.class,
                                sent))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sent_at IS NULL FROM notifications WHERE id = ?",
                                Boolean.class,
                                failed))
                .isTrue();
        assertThat(repository.claim(sent, Duration.ofMinutes(2)))
                .as("a SENT row is never claimed again")
                .isEmpty();
        assertThat(repository.claim(failed, Duration.ofMinutes(2))).isEmpty();
    }

    @Test
    @DisplayName("failPending fails only the event's PENDING rows (AC35.7)")
    void failPending_isScopedToTheEvent() {
        var event = UUID.randomUUID();
        var other = UUID.randomUUID();
        var ann = user();
        var bob = user();
        repository.insertIfAbsent(
                List.of(
                        row(event, ann, "EMAIL"),
                        row(event, ann, "IN_APP"),
                        row(other, bob, "EMAIL")));
        repository.markSent(onlyRowOf(ann, "IN_APP"));

        assertThat(repository.failPending(event)).isEqualTo(1);

        assertThat(statusOf(onlyRowOf(ann, "EMAIL"))).isEqualTo("FAILED");
        assertThat(statusOf(onlyRowOf(ann, "IN_APP"))).isEqualTo("SENT");
        assertThat(statusOf(onlyRowOf(bob, "EMAIL"))).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the oldest PENDING row is reported, and none once everything is resolved")
    void oldestPendingCreatedAt() {
        var ann = user();
        repository.insertIfAbsent(List.of(row(UUID.randomUUID(), ann, "EMAIL")));
        var before = repository.oldestPendingCreatedAt();

        assertThat(before).isPresent();
        repository.markSent(onlyRowOf(ann, "EMAIL"));
        var remaining =
                jdbc.queryForObject(
                        "SELECT count(*) FROM notifications WHERE status = 'PENDING'",
                        Integer.class);
        assertThat(repository.oldestPendingCreatedAt().isPresent()).isEqualTo(remaining > 0);
    }
}
