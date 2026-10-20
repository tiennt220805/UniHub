package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.event.WorkshopRescheduledIntegrationEvent;
import com.unihub.workshop.application.command.FieldChange;
import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.port.out.WorkshopMetrics;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.InvalidWorkshopException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopBuilder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class UpdateWorkshopTest {

    private static final Actor ACTOR = new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_EDIT"));
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final Instant NEW_START = Instant.parse("2026-12-02T09:00:00Z");
    private static final Instant NEW_END = Instant.parse("2026-12-02T11:00:00Z");

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final InMemoryWorkshopCache cache = new InMemoryWorkshopCache();
    private final WorkshopMetrics metrics = mock(WorkshopMetrics.class);
    private final UpdateWorkshop useCase =
            new UpdateWorkshop(
                    workshops,
                    eventPublisher,
                    auditTrail,
                    cache,
                    metrics,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<WorkshopBuilder> terminal() {
        return Stream.of(aCancelledWorkshop(), aCompletedWorkshop());
    }

    /** A command that changes nothing; tests override only the field they are about. */
    private static UpdateWorkshopCommand.Builder patch(UUID id) {
        return UpdateWorkshopCommand.builder(ACTOR, id);
    }

    private Workshop stored(Workshop workshop) {
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));
        return workshop;
    }

    @Test
    @DisplayName("only the fields present in the command change; the rest keep their stored values")
    void update_partial() {
        var workshop = stored(aPublishedWorkshop().build());

        var result =
                useCase.update(
                        patch(workshop.id().value())
                                .title(FieldChange.to("Renamed"))
                                .capacity(FieldChange.to(60))
                                .build());

        assertThat(result.title()).isEqualTo("Renamed");
        assertThat(result.capacity()).isEqualTo(60);
        assertThat(result.location()).isEqualTo(workshop.location());
        assertThat(result.description()).isEqualTo(workshop.description());
        assertThat(result.status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("an explicit null clears description and speakerBio; absent leaves them alone")
    void update_explicitNullClears() {
        var workshop = stored(aDraftWorkshop().build());

        var result =
                useCase.update(
                        patch(workshop.id().value())
                                .description(FieldChange.to(null))
                                .speakerBio(FieldChange.to(null))
                                .build());

        assertThat(result.description()).isNull();
        assertThat(result.speakerBio()).isNull();
        assertThat(result.title()).isEqualTo(workshop.title());
    }

    @Test
    @DisplayName("a request with only startTime/endTime is a reschedule; status is unchanged")
    void update_rescheduleOnly() {
        var workshop = stored(aPublishedWorkshop().build());

        var result =
                useCase.update(
                        patch(workshop.id().value())
                                .startTime(FieldChange.to(NEW_START))
                                .endTime(FieldChange.to(NEW_END))
                                .build());

        assertThat(result.startTime()).isEqualTo(NEW_START);
        assertThat(result.endTime()).isEqualTo(NEW_END);
        assertThat(result.status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("moving only endTime is checked against the stored startTime")
    void update_onlyEndTime_checkedAgainstStoredStart() {
        var workshop = stored(aPublishedWorkshop().build());
        var command =
                patch(workshop.id().value())
                        .endTime(FieldChange.to(workshop.startTime().minusSeconds(1)))
                        .build();

        assertThatThrownBy(() -> useCase.update(command))
                .isInstanceOf(InvalidWorkshopException.class);

        verify(workshops, never()).save(any());
    }

    @Test
    @DisplayName("price is checked against the stored isPaid: a price on a free workshop fails")
    void update_priceOnFreeWorkshop_rejected() {
        var workshop = stored(aPublishedWorkshop().build());
        var command =
                patch(workshop.id().value()).price(FieldChange.to(new BigDecimal("10.00"))).build();

        assertThatThrownBy(() -> useCase.update(command))
                .isInstanceOf(InvalidWorkshopException.class);

        verify(workshops, never()).save(any());
    }

    @Test
    @DisplayName("the price of a paid workshop can be changed")
    void update_paidPrice() {
        var workshop = stored(aPaidWorkshop().build());

        var result =
                useCase.update(
                        patch(workshop.id().value())
                                .price(FieldChange.to(new BigDecimal("99000.00")))
                                .build());

        assertThat(result.price()).isEqualByComparingTo("99000.00");
    }

    @ParameterizedTest
    @MethodSource("terminal")
    @DisplayName("a terminal workshop rejects even an empty update; nothing is saved")
    void update_terminal_rejected(WorkshopBuilder builder) {
        var workshop = builder.build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));

        assertThatThrownBy(() -> useCase.update(patch(workshop.id().value()).build()))
                .isInstanceOf(InvalidWorkshopStateException.class);

        verify(workshops, never()).save(any());
    }

    @Test
    @DisplayName("an unknown workshop is NotFound")
    void update_unknown_notFound() {
        var id = WorkshopId.newId();
        when(workshops.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(patch(id.value()).build()))
                .isInstanceOf(WorkshopNotFoundException.class);
    }

    @Test
    @DisplayName("a lost version race surfaces as OptimisticConflictException")
    void update_lostRace_propagates() {
        var workshop = aPublishedWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any()))
                .thenThrow(new OptimisticConflictException("WORKSHOP", workshop.id().value()));

        assertThatThrownBy(() -> useCase.update(patch(workshop.id().value()).build()))
                .isInstanceOf(OptimisticConflictException.class);
        verify(metrics).editConflicted();
    }

    @Test
    @DisplayName("an edit that succeeds is not counted as a conflict")
    void update_success_doesNotCountConflict() {
        var workshop = aPublishedWorkshop().build();
        stored(workshop);

        useCase.update(patch(workshop.id().value()).build());

        verify(metrics, never()).editConflicted();
    }

    @Test
    @DisplayName("the aggregate handed to save is the loaded one, edited — not a new instance")
    void update_savesEditedAggregate() {
        var workshop = stored(aDraftWorkshop().build());

        useCase.update(patch(workshop.id().value()).build());

        var saved = ArgumentCaptor.forClass(Workshop.class);
        verify(workshops).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(workshop);
    }

    @Test
    @DisplayName(
            "moving the times publishes one WorkshopRescheduled message with old and new times")
    void update_reschedule_publishesRescheduledEvent() {
        var workshop = stored(aPublishedWorkshop().build());
        var previousStart = workshop.startTime();
        var previousEnd = workshop.endTime();

        useCase.update(
                patch(workshop.id().value())
                        .startTime(FieldChange.to(NEW_START))
                        .endTime(FieldChange.to(NEW_END))
                        .build());

        var published = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue())
                .isEqualTo(
                        new WorkshopRescheduledIntegrationEvent(
                                workshop.id().value(),
                                previousStart,
                                previousEnd,
                                NEW_START,
                                NEW_END,
                                NOW));
        assertThat(published.getValue().routingKey())
                .isEqualTo("unihub.workshop.event.rescheduled");
    }

    @Test
    @DisplayName("an edit that leaves the times alone publishes nothing")
    void update_noTimeChange_publishesNothing() {
        var workshop = stored(aPublishedWorkshop().build());

        useCase.update(patch(workshop.id().value()).title(FieldChange.to("Renamed")).build());

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("setting the times to what they already are is not a reschedule: no event")
    void update_sameTimes_publishesNothing() {
        var workshop = stored(aPublishedWorkshop().build());

        useCase.update(
                patch(workshop.id().value())
                        .startTime(FieldChange.to(workshop.startTime()))
                        .endTime(FieldChange.to(workshop.endTime()))
                        .build());

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName(
            "an edit is audited as WORKSHOP_UPDATED only; a reschedule adds WORKSHOP_RESCHEDULED")
    void update_isAudited() {
        var workshop = stored(aPublishedWorkshop().build());
        useCase.update(patch(workshop.id().value()).title(FieldChange.to("Renamed")).build());

        var recorded = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo(AuditActions.WORKSHOP_UPDATED);
        assertThat(recorded.getValue().actor()).isEqualTo(ACTOR);
        assertThat(recorded.getValue().targetId()).isEqualTo(workshop.id().value());
    }

    @Test
    @DisplayName("a reschedule is audited with the previous and the new times")
    void update_reschedule_auditCarriesTheTimes() {
        var workshop = stored(aPublishedWorkshop().build());
        var previousStart = workshop.startTime();

        useCase.update(
                patch(workshop.id().value())
                        .startTime(FieldChange.to(NEW_START))
                        .endTime(FieldChange.to(NEW_END))
                        .build());

        var recorded = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail, atLeastOnce()).record(recorded.capture());
        assertThat(recorded.getAllValues())
                .extracting(AuditEntry::action)
                .containsExactly(AuditActions.WORKSHOP_UPDATED, AuditActions.WORKSHOP_RESCHEDULED);
        var rescheduled = recorded.getAllValues().get(1);
        assertThat(rescheduled.metadata())
                .containsEntry("previousStartTime", previousStart.toString())
                .containsEntry("newStartTime", NEW_START.toString())
                .containsEntry("newEndTime", NEW_END.toString());
    }

    @Test
    @DisplayName("a rejected update publishes nothing and audits nothing")
    void update_rejected_publishesAndAuditsNothing() {
        var workshop = stored(aCancelledWorkshop().build());

        assertThatThrownBy(
                        () ->
                                useCase.update(
                                        patch(workshop.id().value())
                                                .startTime(FieldChange.to(NEW_START))
                                                .endTime(FieldChange.to(NEW_END))
                                                .build()))
                .isInstanceOf(InvalidWorkshopStateException.class);

        verify(eventPublisher, never()).publish(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    @DisplayName("a successful edit drops the workshop's cached detail and seats")
    void update_invalidatesTheCache() {
        var workshop = stored(aPublishedWorkshop().build());

        useCase.update(patch(workshop.id().value()).title(FieldChange.to("Renamed")).build());

        assertThat(cache.invalidated).containsExactly(workshop.id().value());
    }

    @Test
    @DisplayName("a reschedule drops the cache too")
    void update_reschedule_invalidatesTheCache() {
        var workshop = stored(aPublishedWorkshop().build());

        useCase.update(
                patch(workshop.id().value())
                        .startTime(FieldChange.to(NEW_START))
                        .endTime(FieldChange.to(NEW_END))
                        .build());

        assertThat(cache.invalidated).containsExactly(workshop.id().value());
    }

    @Test
    @DisplayName("a rejected edit leaves the cache alone")
    void update_rejected_doesNotInvalidate() {
        var workshop = stored(aCancelledWorkshop().build());

        assertThatThrownBy(
                        () ->
                                useCase.update(
                                        patch(workshop.id().value())
                                                .title(FieldChange.to("Nope"))
                                                .build()))
                .isInstanceOf(InvalidWorkshopStateException.class);

        assertThat(cache.invalidated).isEmpty();
    }
}
