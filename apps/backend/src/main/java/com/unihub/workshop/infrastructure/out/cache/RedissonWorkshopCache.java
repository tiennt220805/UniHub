package com.unihub.workshop.infrastructure.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis cache-aside for the workshop catalog (workshop.md §2): plain JSON strings under the
 * documented keys and TTLs.
 *
 * <p><b>Fail-open.</b> The cache is never authoritative, so nothing here throws: an unreachable or
 * misbehaving Redis reads as a miss and the request is served from the database. After a failure
 * the cache is left alone for {@link #BACKOFF}, so a dead Redis costs one timeout per window, not
 * one per request. Invalidation is the exception to that skipping: a {@code DEL} is always
 * attempted, because a stale entry surviving a lost {@code DEL} is the one thing that would make
 * this cache wrong rather than slow.
 */
@Component
class RedissonWorkshopCache implements WorkshopCache {

    static final Duration DETAIL_TTL = Duration.ofSeconds(300);
    static final Duration SEATS_TTL = Duration.ofSeconds(3);
    static final Duration LIST_TTL = Duration.ofSeconds(30);
    static final Duration BACKOFF = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(RedissonWorkshopCache.class);

    private final RedissonClient redis;
    private final ObjectMapper json;
    private final Clock clock;

    /** Plain field: a race only means one extra Redis attempt or one extra skipped read. */
    private volatile Instant skipUntil = Instant.MIN;

    RedissonWorkshopCache(RedissonClient redis, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<WorkshopDetailSnapshot> findDetail(WorkshopId id) {
        // One key for both releases of a rollout: an entry of another shape is a miss, not a value.
        return read(WorkshopCacheKeys.detail(id), WorkshopDetailSnapshot.class)
                .filter(snapshot -> snapshot.shape() == WorkshopDetailSnapshot.SHAPE);
    }

    @Override
    public void putDetail(WorkshopDetailSnapshot snapshot) {
        write(WorkshopCacheKeys.detail(new WorkshopId(snapshot.id())), snapshot, DETAIL_TTL);
    }

    @Override
    public Optional<WorkshopSeatsSnapshot> findSeats(WorkshopId id) {
        return read(WorkshopCacheKeys.seats(id), WorkshopSeatsSnapshot.class);
    }

    @Override
    public void putSeats(WorkshopSeatsSnapshot snapshot) {
        write(WorkshopCacheKeys.seats(new WorkshopId(snapshot.id())), snapshot, SEATS_TTL);
    }

    @Override
    public Optional<WorkshopCatalogPage> findList(SearchWorkshopsCriteria criteria) {
        return read(WorkshopCacheKeys.list(criteria), WorkshopCatalogPage.class);
    }

    @Override
    public void putList(SearchWorkshopsCriteria criteria, WorkshopCatalogPage page) {
        write(WorkshopCacheKeys.list(criteria), page, LIST_TTL);
    }

    @Override
    public void invalidate(WorkshopId id) {
        afterCommit(() -> delete(WorkshopCacheKeys.detail(id), WorkshopCacheKeys.seats(id)));
    }

    @Override
    public void invalidateSeats(WorkshopId id) {
        afterCommit(() -> delete(WorkshopCacheKeys.seats(id)));
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        var raw = guarded("GET " + key, () -> redis.getBucket(key, StringCodec.INSTANCE).get());
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue((String) raw, type));
        } catch (JsonProcessingException | ClassCastException e) {
            // An entry written by another version of the code: a miss, and it expires on its own.
            LOG.warn("Ignoring unreadable cache entry {}: {}", key, e.toString());
            return Optional.empty();
        }
    }

    private void write(String key, Object value, Duration ttl) {
        String serialized;
        try {
            serialized = json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            LOG.warn("Not caching {}: {}", key, e.toString());
            return;
        }
        guarded(
                "SET " + key,
                () -> {
                    redis.getBucket(key, StringCodec.INSTANCE).set(serialized, ttl);
                    return Boolean.TRUE;
                });
    }

    private void delete(String... keys) {
        try {
            redis.getKeys().delete(keys);
        } catch (RuntimeException e) {
            fail("DEL " + String.join(",", keys), e);
        }
    }

    /** Runs a Redis read or write unless the cache is backing off; null means "no answer". */
    private <T> T guarded(String operation, Supplier<T> action) {
        if (clock.instant().isBefore(skipUntil)) {
            return null;
        }
        try {
            return action.get();
        } catch (RuntimeException e) {
            fail(operation, e);
            return null;
        }
    }

    private void fail(String operation, RuntimeException cause) {
        skipUntil = clock.instant().plus(BACKOFF);
        LOG.warn(
                "Redis unavailable for {}; serving from the database for {}: {}",
                operation,
                BACKOFF,
                cause.toString());
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }
}
