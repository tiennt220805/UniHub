package com.unihub.workshop.infrastructure.out.persistence;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the Flyway-migrated {@code workshops} table and the {@code Workshop} aggregate agree: what
 * the domain builds round-trips through the table unchanged, and every illegal state the domain
 * refuses is also refused by the database itself (architecture.md §4.2 — the CHECK constraints are
 * the backstop, never "redundant").
 */
class WorkshopSchemaIT extends RolledBackJdbcSupport {

    @Test
    @DisplayName("a free draft workshop round-trips through the table unchanged")
    void roundTrip_freeDraft() {
        var workshop = aDraftWorkshop().build();

        WorkshopRows.insert(jdbc, workshop);

        assertThat(WorkshopRows.load(jdbc, workshop.id()))
                .usingRecursiveComparison()
                .isEqualTo(workshop);
    }

    @Test
    @DisplayName("a paid published workshop with summary, brochure and seats round-trips unchanged")
    void roundTrip_paidPublished() {
        var workshop =
                aPaidWorkshop()
                        .withPrice(new BigDecimal("150000.50"))
                        .withAiSummary("Summary")
                        .withBrochureS3Key("brochures/abc.pdf")
                        .withCapacity(30)
                        .withSeatsTaken(12)
                        .withVersion(new Version(5))
                        .build();

        WorkshopRows.insert(jdbc, workshop);

        assertThat(WorkshopRows.load(jdbc, workshop.id()))
                .usingRecursiveComparison()
                .isEqualTo(workshop);
    }

    @Test
    @DisplayName("column DEFAULTs equal a freshly drafted workshop's initial state")
    void defaults_matchDraftInitialState() {
        var id = WorkshopRows.insertRaw(jdbc, Map.of());

        var loaded = WorkshopRows.load(jdbc, WorkshopId.of(id));

        assertThat(loaded.status()).isEqualTo(WorkshopStatus.DRAFT);
        assertThat(loaded.seatsTaken()).isZero();
        assertThat(loaded.isPaid()).isFalse();
        assertThat(loaded.price()).isNull();
        assertThat(loaded.version()).isEqualTo(Version.initial());
    }

    @Test
    @DisplayName("created_at and updated_at are filled by the database")
    void timestamps_defaultToNow() {
        var id = WorkshopRows.insertRaw(jdbc, Map.of());

        var row = jdbc.queryForMap("SELECT created_at, updated_at FROM workshops WHERE id = ?", id);

        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    /** Arguments: description, column overrides, SQLSTATE, expected constraint (null if none). */
    static Stream<Arguments> statesTheDatabaseMustReject() {
        var start = Timestamp.valueOf("2026-12-01 09:00:00");
        return Stream.of(
                Arguments.of(
                        "negative capacity",
                        Map.<String, Object>of("capacity", -1),
                        CHECK_VIOLATION,
                        "workshops_capacity_check"),
                Arguments.of(
                        "seats_taken above capacity",
                        Map.<String, Object>of("capacity", 10, "seats_taken", 11),
                        CHECK_VIOLATION,
                        "workshops_check"),
                Arguments.of(
                        "negative seats_taken",
                        Map.<String, Object>of("seats_taken", -1),
                        CHECK_VIOLATION,
                        "workshops_check"),
                Arguments.of(
                        "end_time equal to start_time",
                        Map.<String, Object>of("start_time", start, "end_time", start),
                        CHECK_VIOLATION,
                        "chk_workshop_time_order"),
                Arguments.of(
                        "end_time before start_time",
                        Map.<String, Object>of(
                                "start_time",
                                start,
                                "end_time",
                                Timestamp.valueOf("2026-12-01 08:00:00")),
                        CHECK_VIOLATION,
                        "chk_workshop_time_order"),
                Arguments.of(
                        "free workshop with a price",
                        Map.<String, Object>of("is_paid", false, "price", new BigDecimal("1.00")),
                        CHECK_VIOLATION,
                        "chk_workshop_price"),
                Arguments.of(
                        "paid workshop without a price",
                        Map.<String, Object>of("is_paid", true),
                        CHECK_VIOLATION,
                        "chk_workshop_price"),
                Arguments.of(
                        "paid workshop with zero price",
                        Map.<String, Object>of("is_paid", true, "price", BigDecimal.ZERO),
                        CHECK_VIOLATION,
                        "chk_workshop_price"),
                Arguments.of(
                        "paid workshop with negative price",
                        Map.<String, Object>of("is_paid", true, "price", new BigDecimal("-1")),
                        CHECK_VIOLATION,
                        "chk_workshop_price"),
                Arguments.of(
                        "price with more than 10 integer digits (NUMERIC(12,2) overflow)",
                        Map.<String, Object>of(
                                "is_paid", true, "price", new BigDecimal("12345678901.00")),
                        NUMERIC_OVERFLOW,
                        null),
                Arguments.of(
                        "unknown status",
                        Map.<String, Object>of("status", "ARCHIVED"),
                        CHECK_VIOLATION,
                        "workshops_status_check"),
                Arguments.of(
                        "title over 255 chars",
                        Map.<String, Object>of("title", "x".repeat(256)),
                        VALUE_TOO_LONG,
                        null),
                Arguments.of(
                        "speaker_name over 255 chars",
                        Map.<String, Object>of("speaker_name", "x".repeat(256)),
                        VALUE_TOO_LONG,
                        null),
                Arguments.of(
                        "location over 255 chars",
                        Map.<String, Object>of("location", "x".repeat(256)),
                        VALUE_TOO_LONG,
                        null),
                Arguments.of(
                        "brochure_s3_key over 512 chars",
                        Map.<String, Object>of("brochure_s3_key", "k".repeat(513)),
                        VALUE_TOO_LONG,
                        null),
                Arguments.of(
                        "null title",
                        java.util.Collections.singletonMap("title", null),
                        NOT_NULL_VIOLATION,
                        null),
                Arguments.of(
                        "null speaker_name",
                        java.util.Collections.singletonMap("speaker_name", null),
                        NOT_NULL_VIOLATION,
                        null),
                Arguments.of(
                        "null location",
                        java.util.Collections.singletonMap("location", null),
                        NOT_NULL_VIOLATION,
                        null),
                Arguments.of(
                        "empty title",
                        Map.<String, Object>of("title", ""),
                        CHECK_VIOLATION,
                        "chk_workshop_title_not_blank"),
                Arguments.of(
                        "spaces-only title",
                        Map.<String, Object>of("title", "   "),
                        CHECK_VIOLATION,
                        "chk_workshop_title_not_blank"),
                Arguments.of(
                        "tab/newline-only title",
                        Map.<String, Object>of("title", " \t\n"),
                        CHECK_VIOLATION,
                        "chk_workshop_title_not_blank"),
                Arguments.of(
                        "empty speaker_name",
                        Map.<String, Object>of("speaker_name", ""),
                        CHECK_VIOLATION,
                        "chk_workshop_speaker_name_not_blank"),
                Arguments.of(
                        "spaces-only speaker_name",
                        Map.<String, Object>of("speaker_name", "   "),
                        CHECK_VIOLATION,
                        "chk_workshop_speaker_name_not_blank"),
                Arguments.of(
                        "empty location",
                        Map.<String, Object>of("location", ""),
                        CHECK_VIOLATION,
                        "chk_workshop_location_not_blank"),
                Arguments.of(
                        "spaces-only location",
                        Map.<String, Object>of("location", "   "),
                        CHECK_VIOLATION,
                        "chk_workshop_location_not_blank"));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("statesTheDatabaseMustReject")
    @DisplayName("the database refuses every illegal workshop state the domain refuses")
    void database_rejectsIllegalState(
            String description, Map<String, Object> overrides, String sqlState, String constraint) {
        assertRejected(() -> WorkshopRows.insertRaw(jdbc, overrides), sqlState, constraint);
    }

    @Test
    @DisplayName(
            "claiming a seat on a full workshop is refused by the CHECK, not only by application code")
    void seatIncrement_onFullWorkshop_isRefused() {
        var workshop = aPublishedWorkshop().withCapacity(10).withSeatsTaken(10).build();
        WorkshopRows.insert(jdbc, workshop);

        assertRejected(
                () ->
                        jdbc.update(
                                "UPDATE workshops SET seats_taken = seats_taken + 1 WHERE id = ?",
                                workshop.id().value()),
                CHECK_VIOLATION,
                "workshops_check");
    }

    @Test
    @DisplayName("claiming the last seat on a workshop one short of full succeeds")
    void seatIncrement_onLastSeat_succeeds() {
        var workshop = aPublishedWorkshop().withCapacity(10).withSeatsTaken(9).build();
        WorkshopRows.insert(jdbc, workshop);

        int updated =
                jdbc.update(
                        "UPDATE workshops SET seats_taken = seats_taken + 1 WHERE id = ?",
                        workshop.id().value());

        assertThat(updated).isEqualTo(1);
        assertThat(WorkshopRows.load(jdbc, workshop.id()).seatsTaken()).isEqualTo(10);
    }

    @Test
    @DisplayName("the (status, start_time) index used by the catalog query exists")
    void catalogIndex_exists() {
        var count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE tablename = 'workshops'"
                                + " AND indexname = 'idx_workshops_status_start_time'",
                        Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "two workshops may share every attribute except id (no accidental unique constraint)")
    void duplicates_areAllowed() {
        var first = aPublishedWorkshop().build();
        var second = aPublishedWorkshop().withId(WorkshopId.of(UUID.randomUUID())).build();

        WorkshopRows.insert(jdbc, first);
        WorkshopRows.insert(jdbc, second);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM workshops", Integer.class))
                .isEqualTo(2);
    }
}
