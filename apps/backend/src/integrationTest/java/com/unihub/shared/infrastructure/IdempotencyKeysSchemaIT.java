package com.unihub.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Migration {@code V3} (database-schema.md §10.1), the {@code idempotency_keys} half: lifecycle
 * CHECKs and the three-part unique key. Each violation is asserted by constraint name — SQLSTATE
 * alone cannot tell two CHECKs apart — and each violating row breaks exactly one rule. The {@code
 * payments} half is {@code PaymentSchemaIT}.
 */
class IdempotencyKeysSchemaIT extends RolledBackJdbcSupport {

    private static final String KEY_INSERT =
            "INSERT INTO idempotency_keys (idempotency_key, scope, actor_user_id,"
                    + " request_fingerprint, status, result_type, result_body, completed_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)";

    private final UUID actor = UUID.randomUUID();

    // ---------- idempotency_keys ----------

    @Test
    @DisplayName("an IN_PROGRESS key carries no result; a COMPLETED key carries result and time")
    void validLifecycleRows_areAccepted() {
        insertKey("k1", "REGISTRATION_CREATE", actor, "IN_PROGRESS", null, null, false);
        insertKey("k2", "REGISTRATION_CREATE", actor, "COMPLETED", "Confirmed", "{}", true);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM idempotency_keys WHERE actor_user_id = ?",
                                Integer.class,
                                actor))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the same key is allowed for another actor and for another scope")
    void sameKey_otherActorOrScope_isAccepted() {
        insertKey("k", "REGISTRATION_CREATE", actor, "IN_PROGRESS", null, null, false);
        insertKey("k", "REGISTRATION_CREATE", UUID.randomUUID(), "IN_PROGRESS", null, null, false);
        insertKey("k", "PAYMENT_CHARGE", actor, "IN_PROGRESS", null, null, false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the same (scope, actor, key) twice is a unique violation")
    void sameScopeActorKey_isRejected() {
        insertKey("k", "REGISTRATION_CREATE", actor, "IN_PROGRESS", null, null, false);

        assertRejected(
                () ->
                        insertKey(
                                "k",
                                "REGISTRATION_CREATE",
                                actor,
                                "IN_PROGRESS",
                                null,
                                null,
                                false),
                UNIQUE_VIOLATION,
                "uq_idempotency_keys_scope_actor_key");
    }

    @Test
    @DisplayName("a status outside IN_PROGRESS/COMPLETED is rejected")
    void unknownStatus_isRejected() {
        assertRejected(
                () -> insertKey("k", "REGISTRATION_CREATE", actor, "DONE", null, null, false),
                CHECK_VIOLATION,
                "ck_idempotency_keys_status");
    }

    @Test
    @DisplayName("an IN_PROGRESS key with a result_type is rejected")
    void inProgressWithResultType_isRejected() {
        assertRejected(
                () ->
                        insertKey(
                                "k",
                                "REGISTRATION_CREATE",
                                actor,
                                "IN_PROGRESS",
                                "Confirmed",
                                "{}",
                                false),
                CHECK_VIOLATION,
                "ck_idempotency_keys_completed_result");
    }

    @Test
    @DisplayName("a COMPLETED key without a result_type is rejected")
    void completedWithoutResultType_isRejected() {
        assertRejected(
                () -> insertKey("k", "REGISTRATION_CREATE", actor, "COMPLETED", null, null, true),
                CHECK_VIOLATION,
                "ck_idempotency_keys_completed_result");
    }

    @Test
    @DisplayName("result_type and result_body come together")
    void resultTypeWithoutBody_isRejected() {
        assertRejected(
                () ->
                        insertKey(
                                "k",
                                "REGISTRATION_CREATE",
                                actor,
                                "COMPLETED",
                                "Confirmed",
                                null,
                                true),
                CHECK_VIOLATION,
                "ck_idempotency_keys_result_pair");
    }

    @Test
    @DisplayName("a COMPLETED key without completed_at is rejected")
    void completedWithoutCompletedAt_isRejected() {
        assertRejected(
                () ->
                        insertKey(
                                "k",
                                "REGISTRATION_CREATE",
                                actor,
                                "COMPLETED",
                                "Confirmed",
                                "{}",
                                false),
                CHECK_VIOLATION,
                "ck_idempotency_keys_completed_at");
    }

    @Test
    @DisplayName("actor_user_id and request_fingerprint are required")
    void actorAndFingerprint_areRequired() {
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO idempotency_keys (idempotency_key, scope,"
                                        + " request_fingerprint, status)"
                                        + " VALUES ('k', 'REGISTRATION_CREATE', 'f', 'IN_PROGRESS')"),
                NOT_NULL_VIOLATION);
    }

    @Test
    @DisplayName("the sweep index and the open-resource index are partial, on the right columns")
    void partialIndexes_exist() {
        assertThat(indexDefinition("idx_idempotency_keys_completed_at"))
                .contains("(completed_at)")
                .contains("status")
                .contains("COMPLETED");
        assertThat(indexDefinition("idx_idempotency_keys_open_resource"))
                .contains("(scope, resource_id)")
                .contains("IN_PROGRESS");
        assertThat(indexDefinition("idx_idempotency_keys_created_at")).isNull();
    }

    // ---------- helpers ----------

    private void insertKey(
            String key,
            String scope,
            UUID actorId,
            String status,
            String resultType,
            String resultBody,
            boolean completed) {
        jdbc.update(
                KEY_INSERT,
                key,
                scope,
                actorId,
                "a".repeat(64),
                status,
                resultType,
                resultBody,
                completed ? OffsetDateTime.now() : null);
    }

    private String indexDefinition(String name) {
        return jdbc
                .query(
                        "SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                        (rs, i) -> rs.getString(1),
                        name)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
