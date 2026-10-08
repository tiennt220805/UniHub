package com.unihub.workshop.domain.model;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.Version;
import com.unihub.workshop.domain.event.WorkshopCancelled;
import com.unihub.workshop.domain.event.WorkshopRescheduled;
import com.unihub.workshop.domain.exception.InvalidWorkshopException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * M-04 state machine ({@code workshops.status}, test-matrix ST §2): DRAFT and PUBLISHED may be
 * cancelled, edited and rescheduled; COMPLETED and CANCELLED are terminal and reject every one of
 * those operations.
 */
class WorkshopLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final Instant NEW_START = Instant.parse("2026-12-02T09:00:00Z");
    private static final Instant NEW_END = Instant.parse("2026-12-02T11:00:00Z");

    static Stream<WorkshopBuilder> terminalWorkshops() {
        return Stream.of(aCancelledWorkshop(), aCompletedWorkshop());
    }

    // ---------- cancel ----------

    @ParameterizedTest
    @EnumSource(
            value = WorkshopStatus.class,
            names = {"DRAFT", "PUBLISHED"})
    @DisplayName("cancel moves a DRAFT or PUBLISHED workshop to CANCELLED and records the event")
    void cancel_fromNonTerminal(WorkshopStatus from) {
        var workshop = aDraftWorkshop().withStatus(from).build();

        workshop.cancel(NOW);

        assertThat(workshop.status()).isEqualTo(WorkshopStatus.CANCELLED);
        assertThat(workshop.pullEvents())
                .containsExactly(new WorkshopCancelled(workshop.id(), NOW));
    }

    @ParameterizedTest
    @MethodSource("terminalWorkshops")
    @DisplayName("cancel rejects a terminal workshop and leaves it untouched, with no event")
    void cancel_rejectsTerminal(WorkshopBuilder builder) {
        var workshop = builder.build();
        var before = workshop.status();

        assertThatThrownBy(() -> workshop.cancel(NOW))
                .isInstanceOf(InvalidWorkshopStateException.class)
                .satisfies(
                        e ->
                                assertThat(((InvalidWorkshopStateException) e).kind())
                                        .isEqualTo(ProblemKind.CONFLICT))
                .hasMessageContaining(before.name());
        assertThat(workshop.status()).isEqualTo(before);
        assertThat(workshop.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("cancelling twice: the second cancel is rejected (CANCELLED is terminal)")
    void cancel_twice() {
        var workshop = aPublishedWorkshop().build();
        workshop.cancel(NOW);

        assertThatThrownBy(() -> workshop.cancel(NOW))
                .isInstanceOf(InvalidWorkshopStateException.class);
    }

    @Test
    @DisplayName("cancel keeps seatsTaken and the version (releasing seats is Registration's job)")
    void cancel_keepsSeatsAndVersion() {
        var workshop = aPublishedWorkshop().withSeatsTaken(7).withVersion(new Version(3)).build();

        workshop.cancel(NOW);

        assertThat(workshop.seatsTaken()).isEqualTo(7);
        assertThat(workshop.version()).isEqualTo(new Version(3));
    }

    // ---------- reschedule ----------

    @ParameterizedTest
    @EnumSource(
            value = WorkshopStatus.class,
            names = {"DRAFT", "PUBLISHED"})
    @DisplayName("reschedule changes the times, keeps the status and records old and new times")
    void reschedule_fromNonTerminal(WorkshopStatus from) {
        var workshop = aDraftWorkshop().withStatus(from).build();
        var oldStart = workshop.startTime();
        var oldEnd = workshop.endTime();

        workshop.reschedule(NEW_START, NEW_END, NOW);

        assertThat(workshop.startTime()).isEqualTo(NEW_START);
        assertThat(workshop.endTime()).isEqualTo(NEW_END);
        assertThat(workshop.status()).isEqualTo(from);
        assertThat(workshop.pullEvents())
                .containsExactly(
                        new WorkshopRescheduled(
                                workshop.id(), oldStart, oldEnd, NEW_START, NEW_END, NOW));
    }

    @ParameterizedTest
    @MethodSource("terminalWorkshops")
    @DisplayName("reschedule rejects a terminal workshop and leaves its times untouched")
    void reschedule_rejectsTerminal(WorkshopBuilder builder) {
        var workshop = builder.build();
        var start = workshop.startTime();
        var end = workshop.endTime();

        assertThatThrownBy(() -> workshop.reschedule(NEW_START, NEW_END, NOW))
                .isInstanceOf(InvalidWorkshopStateException.class);
        assertThat(workshop.startTime()).isEqualTo(start);
        assertThat(workshop.endTime()).isEqualTo(end);
        assertThat(workshop.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("reschedule rejects end <= start and leaves the workshop unchanged, with no event")
    void reschedule_rejectsInvalidRange() {
        var workshop = aPublishedWorkshop().build();
        var start = workshop.startTime();
        var end = workshop.endTime();

        assertThatThrownBy(() -> workshop.reschedule(NEW_END, NEW_END, NOW))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(() -> workshop.reschedule(NEW_END, NEW_START, NOW))
                .isInstanceOf(InvalidWorkshopException.class);

        assertThat(workshop.startTime()).isEqualTo(start);
        assertThat(workshop.endTime()).isEqualTo(end);
        assertThat(workshop.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("rescheduling to the times it already has changes nothing and records no event")
    void reschedule_toSameTimes_isNoOp() {
        var workshop = aPublishedWorkshop().build();

        workshop.reschedule(workshop.startTime(), workshop.endTime(), NOW);

        assertThat(workshop.pullEvents()).isEmpty();
    }

    // ---------- revise (non-time edit) ----------

    @Test
    @DisplayName("revise replaces the editable details and keeps status, seats and version")
    void revise_replacesDetails() {
        var workshop = aPublishedWorkshop().withSeatsTaken(5).withVersion(new Version(2)).build();

        workshop.revise("New title", null, "New Speaker", "bio", "Room 7", 80, null);

        assertThat(workshop.title()).isEqualTo("New title");
        assertThat(workshop.description()).isNull();
        assertThat(workshop.speakerName()).isEqualTo("New Speaker");
        assertThat(workshop.speakerBio()).isEqualTo("bio");
        assertThat(workshop.location()).isEqualTo("Room 7");
        assertThat(workshop.capacity()).isEqualTo(80);
        assertThat(workshop.status()).isEqualTo(WorkshopStatus.PUBLISHED);
        assertThat(workshop.seatsTaken()).isEqualTo(5);
        assertThat(workshop.version()).isEqualTo(new Version(2));
        assertThat(workshop.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("revise can change the price of a paid workshop")
    void revise_paidPrice() {
        var workshop = aPaidWorkshop().build();

        workshop.revise(
                workshop.title(),
                workshop.description(),
                workshop.speakerName(),
                workshop.speakerBio(),
                workshop.location(),
                workshop.capacity(),
                new BigDecimal("250000.00"));

        assertThat(workshop.price()).isEqualByComparingTo("250000.00");
    }

    @MethodSource("terminalWorkshops")
    @ParameterizedTest
    @DisplayName("revise rejects a terminal workshop")
    void revise_rejectsTerminal(WorkshopBuilder builder) {
        var workshop = builder.build();

        assertThatThrownBy(
                        () -> workshop.revise("x", null, "y", null, "z", workshop.capacity(), null))
                .isInstanceOf(InvalidWorkshopStateException.class);
        assertThat(workshop.title()).isNotEqualTo("x");
    }

    @Test
    @DisplayName("revise rejects capacity below the seats already taken")
    void revise_rejectsCapacityBelowSeatsTaken() {
        var workshop = aPublishedWorkshop().withCapacity(50).withSeatsTaken(20).build();

        assertThatThrownBy(
                        () ->
                                workshop.revise(
                                        workshop.title(),
                                        null,
                                        workshop.speakerName(),
                                        null,
                                        workshop.location(),
                                        19,
                                        null))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThat(workshop.capacity()).isEqualTo(50);
    }

    @Test
    @DisplayName("revise accepts capacity equal to the seats already taken")
    void revise_acceptsCapacityEqualToSeatsTaken() {
        var workshop = aPublishedWorkshop().withCapacity(50).withSeatsTaken(20).build();

        workshop.revise(
                workshop.title(),
                null,
                workshop.speakerName(),
                null,
                workshop.location(),
                20,
                null);

        assertThat(workshop.capacity()).isEqualTo(20);
    }

    @Test
    @DisplayName("revise rejects a price on a free workshop and a missing price on a paid one")
    void revise_priceMustMatchIsPaid() {
        var free = aPublishedWorkshop().build();
        var paid = aPaidWorkshop().build();

        assertThatThrownBy(
                        () ->
                                free.revise(
                                        free.title(),
                                        null,
                                        free.speakerName(),
                                        null,
                                        free.location(),
                                        free.capacity(),
                                        new BigDecimal("10.00")))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(
                        () ->
                                paid.revise(
                                        paid.title(),
                                        null,
                                        paid.speakerName(),
                                        null,
                                        paid.location(),
                                        paid.capacity(),
                                        null))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThat(free.price()).isNull();
        assertThat(paid.price()).isNotNull();
    }

    @Test
    @DisplayName("revise rejects blank title and text over 255 characters, leaving state intact")
    void revise_rejectsInvalidText() {
        var workshop = aPublishedWorkshop().build();
        var title = workshop.title();

        assertThatThrownBy(
                        () ->
                                workshop.revise(
                                        " ",
                                        null,
                                        workshop.speakerName(),
                                        null,
                                        workshop.location(),
                                        workshop.capacity(),
                                        null))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(
                        () ->
                                workshop.revise(
                                        workshop.title(),
                                        null,
                                        "s".repeat(256),
                                        null,
                                        workshop.location(),
                                        workshop.capacity(),
                                        null))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThat(workshop.title()).isEqualTo(title);
    }

    // ---------- events ----------

    @Test
    @DisplayName("pullEvents drains: a second call returns nothing")
    void pullEvents_drains() {
        var workshop = aPublishedWorkshop().build();
        workshop.cancel(NOW);

        assertThat(workshop.pullEvents()).hasSize(1);
        assertThat(workshop.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("a draft and a restored workshop start with no pending events")
    void freshAggregates_haveNoEvents() {
        assertThat(aDraftWorkshop().build().pullEvents()).isEmpty();
    }
}
