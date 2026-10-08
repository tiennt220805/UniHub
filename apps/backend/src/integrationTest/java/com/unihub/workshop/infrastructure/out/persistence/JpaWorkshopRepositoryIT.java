package com.unihub.workshop.infrastructure.out.persistence;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Tier A persistence adapter against real Postgres (backend-coding-standards.md §9.2): the
 * aggregate round-trips, a stale version is refused, and two concurrent updates of the same row
 * never both win (architecture.md §4.2 — the version is the arbiter, not an in-process check). Rows
 * are committed (the adapter is exercised through real transactions), so each test deletes what it
 * created to keep the shared database empty for other ITs.
 */
@SpringBootTest
class JpaWorkshopRepositoryIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedRows() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
    }

    private Workshop saveNew(Workshop workshop) {
        created.add(workshop.id().value());
        return tx.execute(s -> workshops.save(workshop));
    }

    private Optional<Workshop> find(WorkshopId id) {
        return tx.execute(s -> workshops.findById(id));
    }

    private long storedVersion(WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT version FROM workshops WHERE id = ?", Long.class, id.value());
    }

    @Test
    @DisplayName("a new free draft is inserted and read back unchanged, at the initial version")
    void save_new_roundTrips() {
        var draft = aDraftWorkshop().build();

        var saved = saveNew(draft);

        assertThat(saved)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt")
                .isEqualTo(draft);
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(find(draft.id()))
                .get()
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt")
                .isEqualTo(draft);
        assertThat(storedVersion(draft.id())).isZero();
    }

    @Test
    @DisplayName("a paid workshop keeps its price scale and nullable text through a round trip")
    void save_paid_roundTrips() {
        var paid =
                aPaidWorkshop()
                        .withPrice(new BigDecimal("150000.50"))
                        .withDescription(null)
                        .withSpeakerBio(null)
                        .build();

        saveNew(paid);

        var loaded = find(paid.id()).orElseThrow();
        assertThat(loaded.price()).isEqualByComparingTo("150000.50");
        assertThat(loaded.description()).isNull();
        assertThat(loaded.speakerBio()).isNull();
        assertThat(loaded.status()).isEqualTo(WorkshopStatus.PUBLISHED);
    }

    @Test
    @DisplayName("findById on an unknown id is an empty Optional, not an exception")
    void findById_unknown_isEmpty() {
        assertThat(find(WorkshopId.newId())).isEmpty();
    }

    @Test
    @DisplayName(
            "an update is persisted and the returned aggregate carries the incremented version")
    void save_update_incrementsVersion() {
        var draft = aDraftWorkshop().build();
        saveNew(draft);

        var updated =
                tx.execute(
                        s -> {
                            var loaded = workshops.findById(draft.id()).orElseThrow();
                            loaded.revise(
                                    "Renamed",
                                    loaded.description(),
                                    loaded.speakerName(),
                                    loaded.speakerBio(),
                                    loaded.location(),
                                    loaded.capacity(),
                                    null);
                            return workshops.save(loaded);
                        });

        assertThat(updated.title()).isEqualTo("Renamed");
        assertThat(updated.version()).isEqualTo(new Version(1));
        assertThat(storedVersion(draft.id())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM workshops WHERE id = ?",
                                String.class,
                                draft.id().value()))
                .isEqualTo("Renamed");
    }

    @Test
    @DisplayName("a state change (cancel) is persisted")
    void save_cancel_persistsStatus() {
        var published = aPaidWorkshop().build();
        saveNew(published);

        tx.executeWithoutResult(
                s -> {
                    var loaded = workshops.findById(published.id()).orElseThrow();
                    loaded.cancel(Instant.parse("2026-10-07T08:00:00Z"));
                    workshops.save(loaded);
                });

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM workshops WHERE id = ?",
                                String.class,
                                published.id().value()))
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("an edit leaves seats_taken, ai_summary and brochure_s3_key as they were stored")
    void save_update_keepsColumnsTheEditDoesNotOwn() {
        var workshop =
                aPaidWorkshop()
                        .withCapacity(30)
                        .withSeatsTaken(12)
                        .withAiSummary("Summary")
                        .withBrochureS3Key("w/b.pdf")
                        .build();
        // Written straight to the table: these columns have no write path in this module yet.
        created.add(workshop.id().value());
        WorkshopRows.insert(jdbc, workshop);

        tx.executeWithoutResult(
                s -> {
                    var loaded = workshops.findById(workshop.id()).orElseThrow();
                    loaded.revise(
                            "Renamed",
                            loaded.description(),
                            loaded.speakerName(),
                            loaded.speakerBio(),
                            loaded.location(),
                            loaded.capacity(),
                            loaded.price());
                    workshops.save(loaded);
                });

        var row = jdbc.queryForMap("SELECT * FROM workshops WHERE id = ?", workshop.id().value());
        assertThat(row.get("seats_taken")).isEqualTo(12);
        assertThat(row.get("ai_summary")).isEqualTo("Summary");
        assertThat(row.get("brochure_s3_key")).isEqualTo("w/b.pdf");
    }

    @Test
    @DisplayName("saving an aggregate loaded before another update committed is refused")
    void save_staleVersion_isRefused() {
        var draft = aDraftWorkshop().build();
        saveNew(draft);
        var stale = find(draft.id()).orElseThrow();
        tx.executeWithoutResult(
                s -> {
                    var fresh = workshops.findById(draft.id()).orElseThrow();
                    fresh.cancel(Instant.parse("2026-10-07T08:00:00Z"));
                    workshops.save(fresh);
                });

        stale.revise(
                "Lost update",
                stale.description(),
                stale.speakerName(),
                stale.speakerBio(),
                stale.location(),
                stale.capacity(),
                null);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> workshops.save(stale)))
                .isInstanceOf(OptimisticConflictException.class);
        assertThat(storedVersion(draft.id())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM workshops WHERE id = ?",
                                String.class,
                                draft.id().value()))
                .isNotEqualTo("Lost update");
    }

    @Test
    @DisplayName("two concurrent updates of one workshop: exactly one commits, the other conflicts")
    void save_concurrentUpdates_exactlyOneWins() throws Exception {
        var draft = aDraftWorkshop().build();
        saveNew(draft);
        var bothLoaded = new CyclicBarrier(2);

        var outcomes =
                List.of("A", "B").stream()
                        .map(
                                title ->
                                        CompletableFuture.supplyAsync(
                                                () -> {
                                                    try {
                                                        tx.executeWithoutResult(
                                                                s -> {
                                                                    var loaded =
                                                                            workshops
                                                                                    .findById(
                                                                                            draft
                                                                                                    .id())
                                                                                    .orElseThrow();
                                                                    await(bothLoaded);
                                                                    loaded.revise(
                                                                            "Title " + title,
                                                                            loaded.description(),
                                                                            loaded.speakerName(),
                                                                            loaded.speakerBio(),
                                                                            loaded.location(),
                                                                            loaded.capacity(),
                                                                            null);
                                                                    workshops.save(loaded);
                                                                });
                                                        return "committed";
                                                    } catch (OptimisticConflictException e) {
                                                        return "conflict";
                                                    }
                                                }))
                        .toList();

        var results = new ArrayList<String>();
        for (var outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).containsExactlyInAnyOrder("committed", "conflict");
        assertThat(storedVersion(draft.id())).isEqualTo(1);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
