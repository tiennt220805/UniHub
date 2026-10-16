package com.unihub.shared.infrastructure.out.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.port.out.StoredResult;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-20's store half (INV-02, backend-coding-standards.md §21) against a real Master and streaming
 * Replica, through the Spring proxies — the transaction and Master-routing rules live on the port.
 * Rows are committed by design (a lock wait needs two real transactions), so each test deletes what
 * it wrote.
 */
@SpringBootTest
class JdbcIdempotencyStoreIT extends ReplicaIntegrationTestSupport {

    private static final IdempotencyScope REGISTRATION = IdempotencyScope.REGISTRATION_CREATE;
    private static final IdempotencyScope PAYMENT = IdempotencyScope.PAYMENT_CHARGE;
    private static final String FINGERPRINT = "a".repeat(64);
    // A programming error, not a business outcome. The @Repository adapter's exception translation
    // rewraps the IllegalStateException, so the message is what identifies it.
    private static final String NOT_IN_PROGRESS = "No IN_PROGRESS idempotency key";

    /** Shaped like a module's id value object (e.g. RegistrationId). */
    record SampleId(UUID value) {}

    sealed interface SampleResult {
        record Confirmed(UUID id, Instant at) implements SampleResult {}

        record Reserved(SampleId id, Instant holdExpiresAt) implements SampleResult {}

        record SoldOut() implements SampleResult {}
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "unihub.datasource.replica.url", ReplicaIntegrationTestSupport::replicaJdbcUrl);
        registry.add("unihub.security.jwt.secret", () -> "i".repeat(48));
        registry.add("unihub.jobs.idempotency-cleanup.enabled", () -> "false");
    }

    @Autowired private IdempotencyStore store;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final UserId actor = UserId.newId();
    private final String key = UUID.randomUUID().toString();
    private final ExecutorService threads = Executors.newCachedThreadPool();

    /**
     * A transaction the test opens by hand; rolled back in teardown if an assertion left it open.
     */
    private TransactionStatus held;

    private TransactionStatus holdTransaction() {
        held = transactions.getTransaction(new DefaultTransactionDefinition());
        return held;
    }

    @AfterEach
    void deleteKeys() {
        if (held != null && !held.isCompleted()) {
            transactions.rollback(held);
        }
        threads.shutdownNow();
        jdbc.update("DELETE FROM idempotency_keys");
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactions);
    }

    private TransactionTemplate inReadOnlyTransaction() {
        var template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return template;
    }

    private IdempotencyRecord find() {
        return store.find(REGISTRATION, actor, key).orElse(null);
    }

    // ---------- begin / complete / find ----------

    @Test
    @DisplayName("find is empty for a key never seen")
    void find_unknownKey_isEmpty() {
        assertThat(store.find(REGISTRATION, actor, key)).isEmpty();
    }

    @Test
    @DisplayName(
            "begin then complete in one transaction leaves a COMPLETED key carrying the Result")
    void beginThenComplete_roundTrip() {
        var registrationId = UUID.randomUUID();
        var result =
                new SampleResult.Confirmed(registrationId, Instant.parse("2026-10-16T10:00:00Z"));

        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(REGISTRATION, actor, key, FINGERPRINT, null);
                            assertThat(find())
                                    .isEqualTo(new IdempotencyRecord.InProgress(FINGERPRINT));
                            store.complete(REGISTRATION, actor, key, result, registrationId);
                        });

        var completed = (IdempotencyRecord.Completed) find();
        assertThat(completed.requestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(completed.result().type()).isEqualTo("Confirmed");
        assertThat(completed.result().body())
                .contains(registrationId.toString())
                .contains("2026-10-16T10:00:00Z");
        var row =
                jdbc.queryForMap(
                        "SELECT status, resource_id, completed_at FROM idempotency_keys"
                                + " WHERE idempotency_key = ?",
                        key);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("resource_id")).isEqualTo(registrationId);
        assertThat(row.get("completed_at")).isNotNull();
    }

    @Test
    @DisplayName("a Result with no fields (a business rejection) is recorded and replayable")
    void emptyResult_roundTrips() {
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(REGISTRATION, actor, key, FINGERPRINT, null);
                            store.complete(
                                    REGISTRATION, actor, key, new SampleResult.SoldOut(), null);
                        });

        var completed = (IdempotencyRecord.Completed) find();
        assertThat(completed.result().type()).isEqualTo("SoldOut");
        assertThat(completed.result().body()).isEqualTo("{}");
    }

    @Test
    @DisplayName("a stored Result is rebuilt from type + body by the caller's sealed interface")
    void storedResult_rebuildsTheVariant() throws Exception {
        var reserved =
                new SampleResult.Reserved(
                        new SampleId(UUID.randomUUID()), Instant.parse("2026-10-16T10:15:00Z"));
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(REGISTRATION, actor, key, FINGERPRINT, null);
                            store.complete(REGISTRATION, actor, key, reserved, null);
                        });
        var otherKey = UUID.randomUUID().toString();
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(REGISTRATION, actor, otherKey, FINGERPRINT, null);
                            store.complete(
                                    REGISTRATION,
                                    actor,
                                    otherKey,
                                    new SampleResult.SoldOut(),
                                    null);
                        });

        var first = (IdempotencyRecord.Completed) find();
        var second =
                (IdempotencyRecord.Completed)
                        store.find(REGISTRATION, actor, otherKey).orElseThrow();

        // What IdempotentReplay (checklist 3.3) will do: pick the permitted subclass by simple
        // name, then let the application's ObjectMapper rebuild it.
        assertThat(replay(first.result(), SampleResult.class)).isEqualTo(reserved);
        assertThat(replay(second.result(), SampleResult.class))
                .isEqualTo(new SampleResult.SoldOut());
    }

    private <R> R replay(StoredResult stored, Class<R> sealedType) throws Exception {
        for (Class<?> variant : sealedType.getPermittedSubclasses()) {
            if (variant.getSimpleName().equals(stored.type())) {
                return sealedType.cast(json.readValue(stored.body(), variant));
            }
        }
        throw new IllegalStateException("unknown stored result type " + stored.type());
    }

    @Test
    @DisplayName("a begun key committed on its own is visible as IN_PROGRESS (PAYMENT_CHARGE tx1)")
    void committedBegin_isInProgress() {
        var paymentId = UUID.randomUUID();

        inTransaction()
                .executeWithoutResult(
                        status -> store.begin(PAYMENT, actor, key, FINGERPRINT, paymentId));

        assertThat(store.find(PAYMENT, actor, key))
                .contains(new IdempotencyRecord.InProgress(FINGERPRINT));
    }

    @Test
    @DisplayName("the same key under another actor or another scope is a different key")
    void keyIsScopedByActorAndScope() {
        inTransaction()
                .executeWithoutResult(
                        status -> store.begin(REGISTRATION, actor, key, FINGERPRINT, null));

        assertThat(store.find(REGISTRATION, UserId.newId(), key)).isEmpty();
        assertThat(store.find(PAYMENT, actor, key)).isEmpty();
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(REGISTRATION, UserId.newId(), key, FINGERPRINT, null);
                            store.begin(PAYMENT, actor, key, FINGERPRINT, null);
                        });
    }

    @Test
    @DisplayName("complete without a begun key, or on an already completed key, is refused")
    void complete_requiresAnInProgressKey() {
        assertThatThrownBy(
                        () ->
                                inTransaction()
                                        .executeWithoutResult(
                                                status ->
                                                        store.complete(
                                                                REGISTRATION,
                                                                actor,
                                                                key,
                                                                new SampleResult.SoldOut(),
                                                                null)))
                .hasMessageContaining(NOT_IN_PROGRESS);

        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(REGISTRATION, actor, key, FINGERPRINT, null);
                            store.complete(
                                    REGISTRATION, actor, key, new SampleResult.SoldOut(), null);
                        });

        assertThatThrownBy(
                        () ->
                                inTransaction()
                                        .executeWithoutResult(
                                                status ->
                                                        store.complete(
                                                                REGISTRATION,
                                                                actor,
                                                                key,
                                                                new SampleResult.Confirmed(
                                                                        UUID.randomUUID(),
                                                                        Instant.now()),
                                                                null)))
                .hasMessageContaining(NOT_IN_PROGRESS);
        assertThat(((IdempotencyRecord.Completed) find()).result().type()).isEqualTo("SoldOut");
    }

    // ---------- same transaction as the effect ----------

    @Test
    @DisplayName("every write needs the caller's transaction (MANDATORY)")
    void writes_requireATransaction() {
        var anyId = UUID.randomUUID();
        var result = new SampleResult.SoldOut();

        assertThatThrownBy(() -> store.begin(REGISTRATION, actor, key, FINGERPRINT, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.complete(REGISTRATION, actor, key, result, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.completeOpenFor(PAYMENT, anyId, result))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.abandonOpenFor(PAYMENT, anyId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("a rolled-back transaction leaves no key behind, begun or completed")
    void rollback_leavesNoKey() {
        assertThatThrownBy(
                        () ->
                                inTransaction()
                                        .executeWithoutResult(
                                                status -> {
                                                    store.begin(
                                                            REGISTRATION,
                                                            actor,
                                                            key,
                                                            FINGERPRINT,
                                                            null);
                                                    store.complete(
                                                            REGISTRATION,
                                                            actor,
                                                            key,
                                                            new SampleResult.SoldOut(),
                                                            null);
                                                    throw new IllegalStateException(
                                                            "business write failed");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.find(REGISTRATION, actor, key)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class))
                .isZero();
    }

    // ---------- concurrent requests with the same key ----------

    @Test
    @DisplayName("a second begin waits for the first; if the first commits it gets InUse")
    void concurrentBegin_firstCommits_secondGetsInUse() throws Exception {
        var first = holdTransaction();
        store.begin(REGISTRATION, actor, key, FINGERPRINT, null);

        Future<Object> second = threads.submit(() -> beginInOwnTransaction());
        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                .as("blocked on the unique index while the first transaction is open")
                .isInstanceOf(TimeoutException.class);
        transactions.commit(first);

        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IdempotencyKeyInUseException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                                Integer.class,
                                key))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("if the first transaction rolls back, the second begin proceeds")
    void concurrentBegin_firstRollsBack_secondProceeds() throws Exception {
        var first = holdTransaction();
        store.begin(REGISTRATION, actor, key, FINGERPRINT, null);

        Future<Object> second = threads.submit(() -> beginInOwnTransaction());
        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        transactions.rollback(first);

        second.get(10, TimeUnit.SECONDS);
        assertThat(store.find(REGISTRATION, actor, key))
                .contains(new IdempotencyRecord.InProgress(FINGERPRINT));
    }

    private Object beginInOwnTransaction() {
        inTransaction()
                .executeWithoutResult(
                        status -> store.begin(REGISTRATION, actor, key, FINGERPRINT, null));
        return null;
    }

    // ---------- reconciliation (G2) and compensation (G1b) ----------

    @Test
    @DisplayName("completeOpenFor completes only the open key of that scope and resource")
    void completeOpenFor_touchesOnlyTheOpenKey() {
        var paymentId = UUID.randomUUID();
        var otherPayment = UUID.randomUUID();
        var otherKey = UUID.randomUUID().toString();
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(PAYMENT, actor, key, FINGERPRINT, paymentId);
                            store.begin(PAYMENT, actor, otherKey, FINGERPRINT, otherPayment);
                            store.begin(REGISTRATION, actor, key, FINGERPRINT, paymentId);
                        });

        inTransaction()
                .executeWithoutResult(
                        status ->
                                store.completeOpenFor(
                                        PAYMENT, paymentId, new SampleResult.SoldOut()));

        assertThat(store.find(PAYMENT, actor, key).orElseThrow())
                .isInstanceOf(IdempotencyRecord.Completed.class);
        assertThat(store.find(PAYMENT, actor, otherKey))
                .as("another payment's key")
                .contains(new IdempotencyRecord.InProgress(FINGERPRINT));
        assertThat(store.find(REGISTRATION, actor, key))
                .as("another scope")
                .contains(new IdempotencyRecord.InProgress(FINGERPRINT));
    }

    @Test
    @DisplayName(
            "completeOpenFor never rewrites a COMPLETED key, and does nothing when none is open")
    void completeOpenFor_leavesCompletedKeysAlone() {
        var paymentId = UUID.randomUUID();
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(PAYMENT, actor, key, FINGERPRINT, paymentId);
                            store.complete(
                                    PAYMENT, actor, key, new SampleResult.SoldOut(), paymentId);
                        });

        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.completeOpenFor(
                                    PAYMENT,
                                    paymentId,
                                    new SampleResult.Confirmed(paymentId, Instant.now()));
                            store.completeOpenFor(
                                    PAYMENT, UUID.randomUUID(), new SampleResult.SoldOut());
                        });

        assertThat(
                        ((IdempotencyRecord.Completed)
                                        store.find(PAYMENT, actor, key).orElseThrow())
                                .result()
                                .type())
                .isEqualTo("SoldOut");
    }

    @Test
    @DisplayName("abandonOpenFor deletes only the open key of that scope and resource")
    void abandonOpenFor_deletesOnlyTheOpenKey() {
        var paymentId = UUID.randomUUID();
        var completedKey = UUID.randomUUID().toString();
        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.begin(PAYMENT, actor, key, FINGERPRINT, paymentId);
                            store.begin(PAYMENT, actor, completedKey, FINGERPRINT, paymentId);
                            store.complete(
                                    PAYMENT,
                                    actor,
                                    completedKey,
                                    new SampleResult.SoldOut(),
                                    paymentId);
                            store.begin(REGISTRATION, actor, key, FINGERPRINT, paymentId);
                        });

        inTransaction()
                .executeWithoutResult(
                        status -> {
                            store.abandonOpenFor(PAYMENT, paymentId);
                            store.abandonOpenFor(PAYMENT, UUID.randomUUID());
                        });

        assertThat(store.find(PAYMENT, actor, key)).as("open key deleted").isEmpty();
        assertThat(store.find(PAYMENT, actor, completedKey)).as("COMPLETED key kept").isPresent();
        assertThat(store.find(REGISTRATION, actor, key)).as("another scope kept").isPresent();
    }

    // ---------- find always reads the Master ----------

    @Test
    @DisplayName(
            "find sees a Master write while the Replica is behind — also as a read-only caller")
    void find_readsTheMaster_notTheStaleReplica() throws Exception {
        var paymentId = UUID.randomUUID();

        withReplayPaused(
                () -> {
                    inTransaction()
                            .executeWithoutResult(
                                    status -> {
                                        store.begin(PAYMENT, actor, key, FINGERPRINT, paymentId);
                                        store.complete(
                                                PAYMENT,
                                                actor,
                                                key,
                                                new SampleResult.SoldOut(),
                                                paymentId);
                                    });

                    // Control: the Replica really is behind, and a plain read-only transaction
                    // really goes there — so a find that wrongly followed it would return empty.
                    Integer rowsSeenByReplica =
                            inReadOnlyTransaction()
                                    .execute(
                                            status ->
                                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM idempotency_keys",
                                                            Integer.class));
                    assertThat(rowsSeenByReplica)
                            .as("a read-only transaction without @ReadFromPrimary hits the Replica")
                            .isZero();

                    assertThat(store.find(PAYMENT, actor, key))
                            .as("find called with no transaction")
                            .isPresent();
                    Optional<IdempotencyRecord> fromReadOnlyCaller =
                            inReadOnlyTransaction()
                                    .execute(status -> store.find(PAYMENT, actor, key));
                    assertThat(fromReadOnlyCaller)
                            .as("find as the first statement of a caller's read-only transaction")
                            .isPresent();
                });

        awaitReplicaCaughtUp();
    }
}
