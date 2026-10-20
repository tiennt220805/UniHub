package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.command.PublishWorkshopCommand;
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

class PublishWorkshopTest {

    private static final Actor ACTOR = new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_EDIT"));
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final InMemoryWorkshopCache cache = new InMemoryWorkshopCache();
    private final PublishWorkshop useCase =
            new PublishWorkshop(workshops, auditTrail, cache, Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<WorkshopBuilder> notDraft() {
        return Stream.of(aPublishedWorkshop(), aCancelledWorkshop(), aCompletedWorkshop());
    }

    @Test
    @DisplayName("publishes a DRAFT: saved as PUBLISHED, cache entry dropped, one audit entry")
    void publish_savesPublished() {
        var workshop = aDraftWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = useCase.publish(new PublishWorkshopCommand(ACTOR, workshop.id().value()));

        var saved = ArgumentCaptor.forClass(Workshop.class);
        verify(workshops).save(saved.capture());
        assertThat(saved.getValue().status().name()).isEqualTo("PUBLISHED");
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(cache.invalidated).containsExactly(workshop.id().value());

        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditActions.WORKSHOP_PUBLISHED);
        assertThat(audit.getValue().targetType()).isEqualTo(AuditTargets.WORKSHOP);
        assertThat(audit.getValue().targetId()).isEqualTo(workshop.id().value());
        assertThat(audit.getValue().occurredAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @MethodSource("notDraft")
    @DisplayName("anything but a DRAFT is rejected: nothing saved, cache kept, nothing audited")
    void publish_notDraft_rejected(WorkshopBuilder builder) {
        var workshop = builder.build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));

        assertThatThrownBy(
                        () ->
                                useCase.publish(
                                        new PublishWorkshopCommand(ACTOR, workshop.id().value())))
                .isInstanceOf(InvalidWorkshopStateException.class);

        verify(workshops, never()).save(any());
        verify(auditTrail, never()).record(any());
        assertThat(cache.invalidated).isEmpty();
    }

    @Test
    @DisplayName("an unknown workshop is NotFound and nothing is saved")
    void publish_unknown_notFound() {
        var id = WorkshopId.newId();
        when(workshops.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.publish(new PublishWorkshopCommand(ACTOR, id.value())))
                .isInstanceOf(WorkshopNotFoundException.class);

        verify(workshops, never()).save(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    @DisplayName("a lost version race surfaces as OptimisticConflictException, not swallowed")
    void publish_lostRace_propagates() {
        var workshop = aDraftWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(workshops.save(any()))
                .thenThrow(new OptimisticConflictException("WORKSHOP", workshop.id().value()));

        assertThatThrownBy(
                        () ->
                                useCase.publish(
                                        new PublishWorkshopCommand(ACTOR, workshop.id().value())))
                .isInstanceOf(OptimisticConflictException.class);

        verify(auditTrail, never()).record(any());
    }
}
