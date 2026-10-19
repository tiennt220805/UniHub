package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.event.WorkshopCancelledIntegrationEvent;
import com.unihub.workshop.application.command.CancelWorkshopCommand;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class CancelWorkshopTest {

    private static final Actor ACTOR = new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_EDIT"));
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final CancelWorkshop useCase =
            new CancelWorkshop(
                    workshops, eventPublisher, auditTrail, Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<WorkshopBuilder> cancellable() {
        return Stream.of(aDraftWorkshop(), aPublishedWorkshop());
    }

    static Stream<WorkshopBuilder> terminal() {
        return Stream.of(aCancelledWorkshop(), aCompletedWorkshop());
    }

    @ParameterizedTest
    @MethodSource("cancellable")
    @DisplayName("cancels a DRAFT or PUBLISHED workshop and saves it as CANCELLED")
    void cancel_savesCancelled(WorkshopBuilder builder) {
        var workshop = builder.build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = useCase.cancel(new CancelWorkshopCommand(ACTOR, workshop.id().value()));

        var saved = ArgumentCaptor.forClass(Workshop.class);
        verify(workshops).save(saved.capture());
        assertThat(saved.getValue().status().name()).isEqualTo("CANCELLED");
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.id()).isEqualTo(workshop.id().value());
    }

    @ParameterizedTest
    @MethodSource("terminal")
    @DisplayName("a terminal workshop is rejected and nothing is saved")
    void cancel_terminal_rejected(WorkshopBuilder builder) {
        var workshop = builder.build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));

        assertThatThrownBy(
                        () ->
                                useCase.cancel(
                                        new CancelWorkshopCommand(ACTOR, workshop.id().value())))
                .isInstanceOf(InvalidWorkshopStateException.class);

        verify(workshops, never()).save(any());
        verify(eventPublisher, never()).publish(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    @DisplayName("an unknown workshop is NotFound and nothing is saved")
    void cancel_unknown_notFound() {
        var id = WorkshopId.newId();
        when(workshops.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.cancel(new CancelWorkshopCommand(ACTOR, id.value())))
                .isInstanceOf(WorkshopNotFoundException.class);

        verify(workshops, never()).save(any());
        verify(eventPublisher, never()).publish(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    @DisplayName("a lost version race surfaces as OptimisticConflictException, not swallowed")
    void cancel_lostRace_propagates() {
        var workshop = aPublishedWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any()))
                .thenThrow(new OptimisticConflictException("WORKSHOP", workshop.id().value()));

        assertThatThrownBy(
                        () ->
                                useCase.cancel(
                                        new CancelWorkshopCommand(ACTOR, workshop.id().value())))
                .isInstanceOf(OptimisticConflictException.class);

        verify(eventPublisher, never()).publish(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    @DisplayName("cancelling publishes exactly one WorkshopCancelled message to the outbox")
    void cancel_publishesCancelledEvent() {
        var workshop = aPublishedWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase.cancel(new CancelWorkshopCommand(ACTOR, workshop.id().value()));

        var published = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue())
                .isEqualTo(new WorkshopCancelledIntegrationEvent(workshop.id().value(), NOW));
        assertThat(published.getValue().routingKey()).isEqualTo("unihub.workshop.event.cancelled");
    }

    @Test
    @DisplayName("the event is published after the cancellation has been saved")
    void cancel_publishesAfterSave() {
        var workshop = aPublishedWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase.cancel(new CancelWorkshopCommand(ACTOR, workshop.id().value()));

        var order = inOrder(workshops, eventPublisher);
        order.verify(workshops).save(any());
        order.verify(eventPublisher).publish(any());
    }

    @Test
    @DisplayName("cancelling is audited as WORKSHOP_CANCELLED by the calling actor")
    void cancel_isAudited() {
        var workshop = aPublishedWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        useCase.cancel(new CancelWorkshopCommand(ACTOR, workshop.id().value()));

        var recorded = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(recorded.capture());
        assertThat(recorded.getValue().actor()).isEqualTo(ACTOR);
        assertThat(recorded.getValue().action()).isEqualTo(AuditActions.WORKSHOP_CANCELLED);
        assertThat(recorded.getValue().targetType()).isEqualTo(AuditTargets.WORKSHOP);
        assertThat(recorded.getValue().targetId()).isEqualTo(workshop.id().value());
        assertThat(recorded.getValue().occurredAt()).isEqualTo(NOW);
    }
}
