package com.unihub.shared.application.port.out;

import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.UserId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the Result of an idempotent operation in the same transaction as its business effect
 * (architecture.md §4.5, backend-coding-standards.md §21, database-schema.md §8.1).
 *
 * <p>Every write needs the caller's transaction ({@code MANDATORY}): a key row that commits alone
 * would break "the key never exists independently of its effect". {@link #find} is the exception: a
 * replay check runs before the entry point opens any transaction (§25.1), so it joins one if there
 * is one and otherwise opens its own read-only transaction on the Master — never the Replica, which
 * may not have the row yet.
 *
 * <p>The {@code key} is the {@code Idempotency-Key} header's UUID in its canonical lowercase form
 * ({@code UUID.toString()}), so the same key in two letter cases is one key. The {@code actor} is
 * the authenticated user (JWT {@code sub}), never a value from the request.
 */
public interface IdempotencyStore {

    /** Consistency-sensitive: always reads the Master. Empty when no row exists. */
    @ReadFromPrimary
    @Transactional(readOnly = true)
    Optional<IdempotencyRecord> find(IdempotencyScope scope, UserId actor, String key);

    /**
     * Inserts the key as {@code IN_PROGRESS}. A concurrent insert of the same key blocks until the
     * other transaction ends; if that one commits, this throws {@link
     * IdempotencyKeyInUseException}.
     *
     * @param resourceId the guarded row when already known (the payment id for a charge), else null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void begin(
            IdempotencyScope scope,
            UserId actor,
            String key,
            String requestFingerprint,
            UUID resourceId);

    /**
     * {@code IN_PROGRESS} to {@code COMPLETED} with the serialized Result. The row must exist and
     * be {@code IN_PROGRESS}, otherwise this is a programming error and throws.
     *
     * @param resourceId the guarded row if it is only known now (the registration id), else null to
     *     keep the one {@link #begin} stored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void complete(IdempotencyScope scope, UserId actor, String key, Object result, UUID resourceId);

    /**
     * Reconciliation (G2): completes the open key of a resolved charge, if one exists. Does nothing
     * when there is none.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void completeOpenFor(IdempotencyScope scope, UUID resourceId, Object result);

    /**
     * Compensation (G1b, D16): deletes the open key of a charge that provably moved no money, so a
     * retry with the same key executes for real. Never touches a {@code COMPLETED} key.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void abandonOpenFor(IdempotencyScope scope, UUID resourceId);
}
