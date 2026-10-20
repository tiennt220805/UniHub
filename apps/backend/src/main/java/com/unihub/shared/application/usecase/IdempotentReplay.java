package com.unihub.shared.application.usecase;

import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.UserId;
import java.util.Optional;

/**
 * The replay decision of an idempotent write (backend-coding-standards.md §21 rule 4), shared by
 * every operation that records its Result. Read-only: it never begins or completes a key.
 *
 * <ul>
 *   <li>no record: empty, the operation runs;
 *   <li>a record made for a different request body (fingerprint): {@link
 *       IdempotencyKeyReusedException}, 422, even while that record is still in progress;
 *   <li>{@code Completed} for the same body: the recorded Result, rebuilt;
 *   <li>{@code InProgress} for the same body: {@link IdempotencyKeyInUseException}, 409.
 * </ul>
 */
public final class IdempotentReplay {

    private IdempotentReplay() {}

    /**
     * @param resultType the operation's sealed Result interface
     */
    public static <R> Optional<R> of(
            IdempotencyStore store,
            IdempotencyScope scope,
            UserId actor,
            String key,
            String requestFingerprint,
            Class<R> resultType) {
        var found = store.find(scope, actor, key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var record = found.get();
        if (!record.requestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyReusedException(scope);
        }
        return switch (record) {
            case IdempotencyRecord.Completed completed ->
                    Optional.of(store.decode(completed.result(), resultType));
            case IdempotencyRecord.InProgress inProgress ->
                    throw new IdempotencyKeyInUseException(scope);
        };
    }
}
