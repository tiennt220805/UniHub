package com.unihub.workshop.domain.model;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.domain.exception.InvalidWorkshopException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkshopTest {

    @Test
    @DisplayName("a new draft starts DRAFT with zero seats taken and the initial version")
    void draft_startsInDraftWithInitialState() {
        var id = WorkshopId.newId();
        var start = Instant.parse("2026-12-01T09:00:00Z");

        var workshop =
                Workshop.draft(
                        id,
                        "Intro to Kubernetes",
                        null,
                        "Ada Lovelace",
                        null,
                        "Room 101",
                        start,
                        start.plusSeconds(3600),
                        50,
                        false,
                        null);

        assertThat(workshop.id()).isEqualTo(id);
        assertThat(workshop.status()).isEqualTo(WorkshopStatus.DRAFT);
        assertThat(workshop.seatsTaken()).isZero();
        assertThat(workshop.version()).isEqualTo(Version.initial());
        assertThat(workshop.aiSummary()).isNull();
        assertThat(workshop.brochureS3Key()).isNull();
    }

    @Test
    @DisplayName("draft enforces the same invariants as restore")
    void draft_rejectsInvalidState() {
        var start = Instant.parse("2026-12-01T09:00:00Z");

        assertThatThrownBy(
                        () ->
                                Workshop.draft(
                                        WorkshopId.newId(),
                                        "Intro",
                                        null,
                                        "Ada",
                                        null,
                                        "Room",
                                        start,
                                        start,
                                        50,
                                        false,
                                        null))
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("a free workshop carries no price")
    void free_hasNullPrice() {
        var workshop = aDraftWorkshop().build();

        assertThat(workshop.isPaid()).isFalse();
        assertThat(workshop.price()).isNull();
    }

    @Test
    @DisplayName("a paid workshop carries its positive price")
    void paid_hasPrice() {
        var workshop = aPaidWorkshop().withPrice(new BigDecimal("150000.00")).build();

        assertThat(workshop.isPaid()).isTrue();
        assertThat(workshop.price()).isEqualByComparingTo("150000.00");
    }

    @Test
    @DisplayName("restore keeps persisted state, including status, seatsTaken and version")
    void restore_keepsPersistedState() {
        var workshop =
                aPublishedWorkshop()
                        .withCapacity(10)
                        .withSeatsTaken(4)
                        .withVersion(new Version(7))
                        .build();

        assertThat(workshop.status()).isEqualTo(WorkshopStatus.PUBLISHED);
        assertThat(workshop.capacity()).isEqualTo(10);
        assertThat(workshop.seatsTaken()).isEqualTo(4);
        assertThat(workshop.version()).isEqualTo(new Version(7));
    }

    @Test
    @DisplayName("accepts a workshop that is exactly full (seatsTaken == capacity)")
    void accepts_whenFull() {
        var workshop = aPublishedWorkshop().withCapacity(10).withSeatsTaken(10).build();

        assertThat(workshop.seatsTaken()).isEqualTo(workshop.capacity());
    }

    @Test
    @DisplayName("accepts zero capacity, as the schema CHECK (capacity >= 0) does")
    void accepts_zeroCapacity() {
        var workshop = aDraftWorkshop().withCapacity(0).build();

        assertThat(workshop.capacity()).isZero();
    }

    @Test
    @DisplayName("rejects seatsTaken above capacity")
    void rejects_seatsTakenAboveCapacity() {
        assertThatThrownBy(() -> aPublishedWorkshop().withCapacity(10).withSeatsTaken(11).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects negative seatsTaken")
    void rejects_negativeSeatsTaken() {
        assertThatThrownBy(() -> aPublishedWorkshop().withSeatsTaken(-1).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects negative capacity")
    void rejects_negativeCapacity() {
        assertThatThrownBy(() -> aDraftWorkshop().withCapacity(-1).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects an end time equal to the start time")
    void rejects_endEqualToStart() {
        var start = Instant.parse("2026-12-01T09:00:00Z");

        assertThatThrownBy(() -> aDraftWorkshop().withStartTime(start).withEndTime(start).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects an end time before the start time")
    void rejects_endBeforeStart() {
        var start = Instant.parse("2026-12-01T09:00:00Z");

        assertThatThrownBy(
                        () ->
                                aDraftWorkshop()
                                        .withStartTime(start)
                                        .withEndTime(start.minusSeconds(1))
                                        .build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects a free workshop that carries a price")
    void rejects_freeWithPrice() {
        assertThatThrownBy(() -> aDraftWorkshop().withPrice(new BigDecimal("1.00")).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects a paid workshop without a price")
    void rejects_paidWithoutPrice() {
        assertThatThrownBy(() -> aPaidWorkshop().withPrice(null).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects a paid workshop with a zero price")
    void rejects_paidWithZeroPrice() {
        assertThatThrownBy(() -> aPaidWorkshop().withPrice(BigDecimal.ZERO).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects a paid workshop with a negative price")
    void rejects_paidWithNegativePrice() {
        assertThatThrownBy(() -> aPaidWorkshop().withPrice(new BigDecimal("-1")).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects blank title, speaker name and location")
    void rejects_blankRequiredText() {
        assertThatThrownBy(() -> aDraftWorkshop().withTitle("  ").build())
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(() -> aDraftWorkshop().withSpeakerName("").build())
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(() -> aDraftWorkshop().withLocation(null).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects text longer than the schema column length")
    void rejects_textLongerThanColumn() {
        assertThatThrownBy(() -> aDraftWorkshop().withTitle("x".repeat(256)).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects speaker name and location longer than their 255-character columns")
    void rejects_speakerNameAndLocationLongerThanColumn() {
        assertThatThrownBy(() -> aDraftWorkshop().withSpeakerName("x".repeat(256)).build())
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(() -> aDraftWorkshop().withLocation("x".repeat(256)).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("rejects a brochure key longer than its 512-character column")
    void rejects_brochureKeyLongerThanColumn() {
        assertThatThrownBy(() -> aDraftWorkshop().withBrochureS3Key("k".repeat(513)).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("accepts text exactly at the column limits (255 / 255 / 255 / 512)")
    void accepts_textAtColumnLimits() {
        var workshop =
                aDraftWorkshop()
                        .withTitle("t".repeat(255))
                        .withSpeakerName("s".repeat(255))
                        .withLocation("l".repeat(255))
                        .withBrochureS3Key("k".repeat(512))
                        .build();

        assertThat(workshop.title()).hasSize(255);
        assertThat(workshop.brochureS3Key()).hasSize(512);
    }

    @Test
    @DisplayName("rejects a price with more than 2 decimal places (NUMERIC(12,2) would round it)")
    void rejects_priceWithMoreThanTwoDecimals() {
        assertThatThrownBy(() -> aPaidWorkshop().withPrice(new BigDecimal("1.234")).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("accepts a price whose extra decimal places are only trailing zeros")
    void accepts_priceWithTrailingZeros() {
        var workshop = aPaidWorkshop().withPrice(new BigDecimal("1.500")).build();

        assertThat(workshop.price()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("rejects a price with more than 10 integer digits (NUMERIC(12,2) would overflow)")
    void rejects_priceWithMoreThanTenIntegerDigits() {
        assertThatThrownBy(
                        () -> aPaidWorkshop().withPrice(new BigDecimal("12345678901.00")).build())
                .isInstanceOf(InvalidWorkshopException.class);
    }

    @Test
    @DisplayName("accepts the largest price NUMERIC(12,2) can hold")
    void accepts_largestRepresentablePrice() {
        var workshop = aPaidWorkshop().withPrice(new BigDecimal("9999999999.99")).build();

        assertThat(workshop.price()).isEqualByComparingTo("9999999999.99");
    }

    @Test
    @DisplayName("the exception is a DomainException reported as CONFLICT")
    void exception_kindIsConflict() {
        assertThatThrownBy(() -> aDraftWorkshop().withCapacity(-1).build())
                .isInstanceOfSatisfying(
                        InvalidWorkshopException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProblemKind.CONFLICT));
    }
}
