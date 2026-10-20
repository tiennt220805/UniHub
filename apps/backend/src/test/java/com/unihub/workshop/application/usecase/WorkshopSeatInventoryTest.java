package com.unihub.workshop.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatClaimOutcome;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.application.port.out.SeatSnapshot;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkshopSeatInventoryTest {

    private static final WorkshopId ID = WorkshopId.newId();

    private final SeatCounter seats = mock(SeatCounter.class);
    private final WorkshopCache cache = mock(WorkshopCache.class);
    private final WorkshopSeatInventory inventory = new WorkshopSeatInventory(seats, cache);

    private static SeatSnapshot snapshot(
            WorkshopStatus status, boolean paid, int taken, int capacity, long version) {
        return new SeatSnapshot(status, paid, taken, capacity, new Version(version));
    }

    private static SeatSnapshot open(int taken, int capacity, long version) {
        return snapshot(WorkshopStatus.PUBLISHED, false, taken, capacity, version);
    }

    @Test
    @DisplayName(
            "a claim on an open workshop with room updates at the read version and reports paid")
    void claim_success() {
        when(seats.read(ID))
                .thenReturn(Optional.of(snapshot(WorkshopStatus.PUBLISHED, true, 4, 5, 7)));
        when(seats.tryClaim(ID, new Version(7))).thenReturn(true);

        var outcome = inventory.claim(ID);

        assertThat(outcome).isEqualTo(new SeatClaimOutcome.Claimed(true));
        verify(cache).invalidateSeats(ID);
    }

    @Test
    @DisplayName("a full workshop is SoldOut without touching the counter")
    void claim_full_isSoldOut() {
        when(seats.read(ID)).thenReturn(Optional.of(open(5, 5, 1)));

        assertThat(inventory.claim(ID)).isEqualTo(new SeatClaimOutcome.SoldOut());
        verify(seats, never()).tryClaim(any(), any());
        verify(cache, never()).invalidateSeats(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = WorkshopStatus.class,
            names = {"DRAFT", "CANCELLED", "COMPLETED"})
    @DisplayName("a workshop that is not PUBLISHED is not open for registration, even when full")
    void claim_notPublished(WorkshopStatus status) {
        when(seats.read(ID)).thenReturn(Optional.of(snapshot(status, false, 5, 5, 1)));

        assertThat(inventory.claim(ID)).isEqualTo(new SeatClaimOutcome.NotOpenForRegistration());
        verify(seats, never()).tryClaim(any(), any());
    }

    @Test
    @DisplayName("an unknown workshop is WorkshopNotFoundException")
    void claim_unknown() {
        when(seats.read(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventory.claim(ID)).isInstanceOf(WorkshopNotFoundException.class);
    }

    @Test
    @DisplayName("a refused update with the last seat gone meanwhile is SoldOut, not a conflict")
    void claim_refusedBecauseFull_isSoldOut() {
        when(seats.read(ID)).thenReturn(Optional.of(open(4, 5, 1)), Optional.of(open(5, 5, 2)));
        when(seats.tryClaim(ID, new Version(1))).thenReturn(false);

        assertThat(inventory.claim(ID)).isEqualTo(new SeatClaimOutcome.SoldOut());
        verify(cache, never()).invalidateSeats(any());
    }

    @Test
    @DisplayName("a refused update because the workshop closed meanwhile is NotOpen")
    void claim_refusedBecauseCancelled_isNotOpen() {
        when(seats.read(ID))
                .thenReturn(
                        Optional.of(open(1, 5, 1)),
                        Optional.of(snapshot(WorkshopStatus.CANCELLED, false, 1, 5, 2)));
        when(seats.tryClaim(ID, new Version(1))).thenReturn(false);

        assertThat(inventory.claim(ID)).isEqualTo(new SeatClaimOutcome.NotOpenForRegistration());
    }

    @Test
    @DisplayName("a refused update with room left is a lost version race: OptimisticConflict")
    void claim_refusedWithRoom_isConflict() {
        when(seats.read(ID)).thenReturn(Optional.of(open(1, 5, 1)), Optional.of(open(1, 5, 2)));
        when(seats.tryClaim(ID, new Version(1))).thenReturn(false);

        assertThatThrownBy(() -> inventory.claim(ID))
                .isInstanceOf(OptimisticConflictException.class);
        verify(cache, never()).invalidateSeats(any());
    }

    @Test
    @DisplayName("release gives one seat back at the read version and drops the seats cache")
    void release_success() {
        when(seats.read(ID)).thenReturn(Optional.of(open(3, 5, 9)));
        when(seats.tryRelease(ID, new Version(9))).thenReturn(true);

        inventory.release(ID);

        verify(cache).invalidateSeats(ID);
    }

    @Test
    @DisplayName("a lost version race on release is OptimisticConflict")
    void release_lostRace() {
        when(seats.read(ID)).thenReturn(Optional.of(open(3, 5, 9)));
        when(seats.tryRelease(ID, new Version(9))).thenReturn(false);

        assertThatThrownBy(() -> inventory.release(ID))
                .isInstanceOf(OptimisticConflictException.class);
    }

    @Test
    @DisplayName("releasing from a workshop with no taken seat is a bug, not a retry")
    void release_nothingTaken() {
        when(seats.read(ID)).thenReturn(Optional.of(open(0, 5, 9)));

        assertThatThrownBy(() -> inventory.release(ID)).isInstanceOf(IllegalStateException.class);
        verify(seats, never()).tryRelease(any(), any());
    }

    @Test
    @DisplayName("releasing on an unknown workshop is WorkshopNotFoundException")
    void release_unknown() {
        when(seats.read(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventory.release(ID))
                .isInstanceOf(WorkshopNotFoundException.class);
    }
}
