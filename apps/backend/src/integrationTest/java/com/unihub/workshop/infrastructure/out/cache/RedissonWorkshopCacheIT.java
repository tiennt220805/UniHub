package com.unihub.workshop.infrastructure.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCatalogEntry;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * M-08, adapter half (workshop.md §2, distributed-coordination.md §5) against a real Redis: the
 * documented keys, plain JSON strings, the three TTLs, and the invalidation rules.
 */
class RedissonWorkshopCacheIT extends IntegrationTestSupport {

    private static RedissonClient redis;

    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC);
    private final RedissonWorkshopCache cache = new RedissonWorkshopCache(redis, json, clock);

    private final WorkshopId id = WorkshopId.newId();
    private final List<String> keysToDelete = new java.util.ArrayList<>();

    @BeforeAll
    static void connect() {
        var config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redis = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        redis.shutdown();
    }

    @AfterEach
    void cleanUp() {
        redis.getKeys().delete(keysToDelete.toArray(String[]::new));
    }

    private String track(String key) {
        keysToDelete.add(key);
        return key;
    }

    private WorkshopDetailSnapshot detail() {
        track(WorkshopCacheKeys.detail(id));
        return new WorkshopDetailSnapshot(
                id.value(),
                "Intro to Kubernetes",
                "Hands-on.",
                null,
                "Ada Lovelace",
                null,
                "Room 101",
                Instant.parse("2026-12-01T09:00:00Z"),
                Instant.parse("2026-12-01T11:00:00Z"),
                50,
                true,
                new BigDecimal("150000.50"),
                true,
                "covers/" + id.value() + "/a.jpg",
                Instant.parse("2026-10-01T00:00:00Z"));
    }

    private WorkshopSeatsSnapshot seats() {
        track(WorkshopCacheKeys.seats(id));
        return new WorkshopSeatsSnapshot(
                id.value(), 12, 50, "PUBLISHED", 3, Instant.parse("2026-10-19T07:59:00Z"));
    }

    private long ttlMillis(String key) {
        return redis.getBucket(key, StringCodec.INSTANCE).remainTimeToLive();
    }

    private boolean exists(String key) {
        return redis.getBucket(key, StringCodec.INSTANCE).isExists();
    }

    @Test
    @DisplayName("detail round-trips, as plain JSON under the documented key, for 300s")
    void detail_roundTrip_keyAndTtl() {
        var snapshot = detail();

        cache.putDetail(snapshot);

        assertThat(cache.findDetail(id)).contains(snapshot);
        var key = "unihub:cache:workshop:detail:v2:" + id.value();
        var raw = (String) redis.getBucket(key, StringCodec.INSTANCE).get();
        assertThat(raw).startsWith("{").contains("\"title\":\"Intro to Kubernetes\"");
        assertThat(raw).as("no storage key leaks into the cache").doesNotContain("brochureS3Key");
        assertThat(ttlMillis(key)).isBetween(295_000L, 300_000L);
    }

    @Test
    @DisplayName("a price keeps its scale through the cache")
    void detail_priceKeepsItsScale() {
        cache.putDetail(detail());

        assertThat(cache.findDetail(id).orElseThrow().price())
                .isEqualTo(new BigDecimal("150000.50"));
    }

    @Test
    @DisplayName("seats round-trip under the documented key for 3s")
    void seats_roundTrip_keyAndTtl() {
        var snapshot = seats();

        cache.putSeats(snapshot);

        assertThat(cache.findSeats(id)).contains(snapshot);
        var key = "unihub:cache:workshop:seats:" + id.value();
        assertThat(ttlMillis(key)).isBetween(1L, 3_000L);
    }

    @Test
    @DisplayName("a search page round-trips under list:{filterHash} for 30s")
    void list_roundTrip_keyAndTtl() {
        var criteria = new SearchWorkshopsCriteria("kube", null, null, null, null, 0, 20);
        var page =
                new WorkshopCatalogPage(
                        List.of(
                                new WorkshopCatalogEntry(
                                        UUID.randomUUID(),
                                        "Intro",
                                        "Room 101",
                                        Instant.parse("2026-12-01T09:00:00Z"),
                                        "PUBLISHED",
                                        "Ada Lovelace",
                                        Instant.parse("2026-12-01T11:00:00Z"),
                                        30,
                                        10,
                                        true,
                                        new BigDecimal("150000.50"),
                                        "covers/x/y.png")),
                        0,
                        20,
                        1L,
                        1);
        var key = track(WorkshopCacheKeys.list(criteria));

        cache.putList(criteria, page);

        assertThat(cache.findList(criteria)).contains(page);
        // The shape generation is part of the key: an entry written by the other release of a
        // rollout (INV-15) has another shape and must never be read as this one.
        assertThat(key).startsWith("unihub:cache:workshop:list:v2:");
        assertThat(ttlMillis(key)).isBetween(25_000L, 30_000L);
    }

    @Test
    @DisplayName("an entry that is not there is an empty Optional")
    void miss_isEmpty() {
        assertThat(cache.findDetail(WorkshopId.newId())).isEmpty();
        assertThat(cache.findSeats(WorkshopId.newId())).isEmpty();
    }

    @Test
    @DisplayName("an unreadable entry is a miss, not an error")
    void garbage_isAMiss() {
        var key = track(WorkshopCacheKeys.detail(id));
        redis.getBucket(key, StringCodec.INSTANCE).set("this is not json");

        assertThat(cache.findDetail(id)).isEmpty();
    }

    @Test
    @DisplayName(
            "invalidate drops detail and seats of that workshop and leaves lists and others alone")
    void invalidate_dropsDetailAndSeatsOnly() {
        var other = WorkshopId.newId();
        var criteria = new SearchWorkshopsCriteria(null, null, null, null, null, 0, 20);
        cache.putDetail(detail());
        cache.putSeats(seats());
        cache.putList(criteria, new WorkshopCatalogPage(List.of(), 0, 20, 0, 0));
        track(WorkshopCacheKeys.list(criteria));
        track(WorkshopCacheKeys.detail(other));
        redis.getBucket(WorkshopCacheKeys.detail(other), StringCodec.INSTANCE).set("{}");

        cache.invalidate(id);

        assertThat(exists(WorkshopCacheKeys.detail(id))).isFalse();
        assertThat(exists(WorkshopCacheKeys.seats(id))).isFalse();
        assertThat(exists(WorkshopCacheKeys.list(criteria))).as("lists expire by TTL").isTrue();
        assertThat(exists(WorkshopCacheKeys.detail(other))).as("another workshop").isTrue();
    }

    @Test
    @DisplayName("invalidateSeats drops the seats entry and keeps the slow-changing detail")
    void invalidateSeats_keepsDetail() {
        cache.putDetail(detail());
        cache.putSeats(seats());

        cache.invalidateSeats(id);

        assertThat(exists(WorkshopCacheKeys.seats(id))).isFalse();
        assertThat(exists(WorkshopCacheKeys.detail(id))).isTrue();
    }

    @Test
    @DisplayName(
            "inside a transaction the keys survive until the commit, and a rollback keeps them")
    void invalidate_waitsForCommit() {
        cache.putDetail(detail());
        cache.putSeats(seats());

        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidate(id);
            assertThat(exists(WorkshopCacheKeys.detail(id))).as("before commit").isTrue();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(exists(WorkshopCacheKeys.detail(id))).as("after rollback").isTrue();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(exists(WorkshopCacheKeys.detail(id))).as("after commit").isFalse();
            assertThat(exists(WorkshopCacheKeys.seats(id))).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
