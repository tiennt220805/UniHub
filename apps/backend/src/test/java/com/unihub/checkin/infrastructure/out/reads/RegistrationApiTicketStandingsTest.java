package com.unihub.checkin.infrastructure.out.reads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.registration.api.TicketLookup;
import com.unihub.registration.api.TicketRegistration;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopSummaries;
import com.unihub.workshop.api.WorkshopSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Translation only: Registration's answer plus Workshop's status become one standing per code. */
class RegistrationApiTicketStandingsTest {

    private final TicketLookup tickets = mock(TicketLookup.class);
    private final WorkshopSummaries workshops = mock(WorkshopSummaries.class);
    private final RegistrationApiTicketStandings standings =
            new RegistrationApiTicketStandings(tickets, workshops);

    private static WorkshopSummary summary(WorkshopId id, boolean cancelled) {
        return new WorkshopSummary(
                id, "Title", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "Room", cancelled);
    }

    @Test
    @DisplayName("a confirmed ticket of a live workshop is a standing that is confirmed and live")
    void confirmedTicket_ofALiveWorkshop() {
        var workshop = WorkshopId.newId();
        var registration = RegistrationId.newId();
        when(tickets.findByCodes(List.of("A")))
                .thenReturn(Map.of("A", new TicketRegistration(registration, workshop, true)));
        when(workshops.find(workshop)).thenReturn(Optional.of(summary(workshop, false)));

        var found = standings.findByCodes(List.of("A"));

        assertThat(found).containsOnlyKeys("A");
        assertThat(found.get("A").registrationId()).isEqualTo(registration);
        assertThat(found.get("A").standing().confirmed()).isTrue();
        assertThat(found.get("A").standing().workshopId()).isEqualTo(workshop);
        assertThat(found.get("A").standing().workshopCancelled()).isFalse();
    }

    @Test
    @DisplayName("a cancelled workshop and an unconfirmed registration are carried through")
    void cancelledWorkshop_andUnconfirmedRegistration() {
        var cancelledWorkshop = WorkshopId.newId();
        var liveWorkshop = WorkshopId.newId();
        when(tickets.findByCodes(List.of("A", "B")))
                .thenReturn(
                        Map.of(
                                "A",
                                new TicketRegistration(
                                        RegistrationId.newId(), cancelledWorkshop, true),
                                "B",
                                new TicketRegistration(
                                        RegistrationId.newId(), liveWorkshop, false)));
        when(workshops.find(cancelledWorkshop))
                .thenReturn(Optional.of(summary(cancelledWorkshop, true)));
        when(workshops.find(liveWorkshop)).thenReturn(Optional.of(summary(liveWorkshop, false)));

        var found = standings.findByCodes(List.of("A", "B"));

        assertThat(found.get("A").standing().workshopCancelled()).isTrue();
        assertThat(found.get("B").standing().confirmed()).isFalse();
    }

    @Test
    @DisplayName("each distinct workshop of the batch is read once, however many tickets it has")
    void eachDistinctWorkshop_isReadOnce() {
        var workshop = WorkshopId.newId();
        when(tickets.findByCodes(List.of("A", "B", "C")))
                .thenReturn(
                        Map.of(
                                "A", new TicketRegistration(RegistrationId.newId(), workshop, true),
                                "B", new TicketRegistration(RegistrationId.newId(), workshop, true),
                                "C",
                                        new TicketRegistration(
                                                RegistrationId.newId(), workshop, true)));
        when(workshops.find(workshop)).thenReturn(Optional.of(summary(workshop, false)));

        assertThat(standings.findByCodes(List.of("A", "B", "C"))).hasSize(3);

        verify(workshops, times(1)).find(workshop);
    }

    @Test
    @DisplayName("a workshop that cannot be read counts as cancelled: the scan is not counted")
    void unreadableWorkshop_failsClosed() {
        var workshop = WorkshopId.newId();
        when(tickets.findByCodes(List.of("A")))
                .thenReturn(
                        Map.of(
                                "A",
                                new TicketRegistration(RegistrationId.newId(), workshop, true)));
        when(workshops.find(workshop)).thenReturn(Optional.empty());

        assertThat(standings.findByCodes(List.of("A")).get("A").standing().workshopCancelled())
                .isTrue();
    }

    @Test
    @DisplayName("no code resolves: Workshop is not asked at all")
    void nothingResolved_asksNoWorkshop() {
        when(tickets.findByCodes(List.of("A"))).thenReturn(Map.of());

        assertThat(standings.findByCodes(List.of("A"))).isEmpty();

        verify(workshops, never()).find(any());
    }
}
