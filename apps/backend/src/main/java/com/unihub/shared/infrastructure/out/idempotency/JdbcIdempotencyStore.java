package com.unihub.shared.infrastructure.out.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.port.out.StoredResult;
import com.unihub.shared.domain.id.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link IdempotencyStore} over {@code idempotency_keys} (database-schema.md §8.1).
 *
 * <p>Plain {@link JdbcTemplate}, not JPA: the unique-constraint violation must surface inside
 * {@link #begin}, at the statement, not later at a flush (§21 rule 6). The transaction and the
 * Master routing of {@link #find} are declared on the port.
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String FIND =
            "SELECT request_fingerprint, status, result_type, result_body::text"
                    + " FROM idempotency_keys"
                    + " WHERE scope = ? AND actor_user_id = ? AND idempotency_key = ?";

    private static final String BEGIN =
            "INSERT INTO idempotency_keys"
                    + " (scope, actor_user_id, idempotency_key, request_fingerprint, status,"
                    + " resource_id) VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?)";

    private static final String COMPLETE =
            "UPDATE idempotency_keys SET status = 'COMPLETED', result_type = ?,"
                    + " result_body = ?::jsonb, resource_id = COALESCE(?, resource_id),"
                    + " completed_at = ?"
                    + " WHERE scope = ? AND actor_user_id = ? AND idempotency_key = ?"
                    + " AND status = 'IN_PROGRESS'";

    private static final String COMPLETE_OPEN_FOR =
            "UPDATE idempotency_keys SET status = 'COMPLETED', result_type = ?,"
                    + " result_body = ?::jsonb, completed_at = ?"
                    + " WHERE scope = ? AND resource_id = ? AND status = 'IN_PROGRESS'";

    private static final String ABANDON_OPEN_FOR =
            "DELETE FROM idempotency_keys"
                    + " WHERE scope = ? AND resource_id = ? AND status = 'IN_PROGRESS'";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcIdempotencyStore(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyScope scope, UserId actor, String key) {
        return jdbc
                .query(
                        FIND,
                        (rs, row) -> {
                            var fingerprint = rs.getString(1);
                            if ("COMPLETED".equals(rs.getString(2))) {
                                return (IdempotencyRecord)
                                        new IdempotencyRecord.Completed(
                                                fingerprint,
                                                new StoredResult(rs.getString(3), rs.getString(4)));
                            }
                            return new IdempotencyRecord.InProgress(fingerprint);
                        },
                        scope.name(),
                        actor.value(),
                        key)
                .stream()
                .findFirst();
    }

    @Override
    public <R> R decode(StoredResult stored, Class<R> resultType) {
        var variants = resultType.getPermittedSubclasses();
        if (variants == null) {
            throw new IllegalArgumentException(resultType.getName() + " is not a sealed type");
        }
        for (Class<?> variant : variants) {
            if (variant.getSimpleName().equals(stored.type())) {
                try {
                    return resultType.cast(json.readValue(stored.body(), variant));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(
                            "Stored result " + stored.type() + " cannot be rebuilt", e);
                }
            }
        }
        throw new IllegalStateException(
                "Stored result type " + stored.type() + " is not a variant of " + resultType);
    }

    @Override
    public void begin(
            IdempotencyScope scope,
            UserId actor,
            String key,
            String requestFingerprint,
            UUID resourceId) {
        try {
            jdbc.update(BEGIN, scope.name(), actor.value(), key, requestFingerprint, resourceId);
        } catch (DuplicateKeyException duplicate) {
            throw new IdempotencyKeyInUseException(scope);
        }
    }

    @Override
    public void complete(
            IdempotencyScope scope, UserId actor, String key, Object result, UUID resourceId) {
        var stored = serialize(result);
        int updated =
                jdbc.update(
                        COMPLETE,
                        stored.type(),
                        stored.body(),
                        resourceId,
                        now(),
                        scope.name(),
                        actor.value(),
                        key);
        if (updated != 1) {
            throw new IllegalStateException(
                    "No IN_PROGRESS idempotency key to complete for scope "
                            + scope
                            + " (begin must run first, in the same transaction)");
        }
    }

    @Override
    public void completeOpenFor(IdempotencyScope scope, UUID resourceId, Object result) {
        Objects.requireNonNull(resourceId, "resourceId");
        var stored = serialize(result);
        jdbc.update(
                COMPLETE_OPEN_FOR, stored.type(), stored.body(), now(), scope.name(), resourceId);
    }

    @Override
    public void abandonOpenFor(IdempotencyScope scope, UUID resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        jdbc.update(ABANDON_OPEN_FOR, scope.name(), resourceId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private StoredResult serialize(Object result) {
        Objects.requireNonNull(result, "result");
        try {
            return new StoredResult(
                    result.getClass().getSimpleName(), json.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Result " + result.getClass().getName() + " is not serializable", e);
        }
    }
}
