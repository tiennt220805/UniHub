package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.command.RequestBrochureUploadCommand;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.Workshop;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** workshop.md §7, issuance (Phase 5 checklist 9.2, M-50). */
class RequestBrochureUploadTest {
    private static final Actor ACTOR =
            new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_CREATE"));
    private static final Instant NOW = Instant.parse("2026-11-01T08:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final InMemoryWorkshopBrochures brochures = new InMemoryWorkshopBrochures();
    private final InMemoryBrochureStorage storage = new InMemoryBrochureStorage(clock);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final RequestBrochureUpload useCase =
            new RequestBrochureUpload(workshops, brochures, storage, audit, clock);

    private Workshop existing(Workshop workshop) {
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        return workshop;
    }

    private RequestBrochureUploadCommand commandFor(Workshop workshop) {
        return new RequestBrochureUploadCommand(workshop.id().value(), ACTOR);
    }

    @Test
    @DisplayName(
            "a ten-minute URL for a fresh {workshop}/{uuid}.pdf key, which the server remembers")
    void issues() {
        var workshop = existing(aPublishedWorkshop().build());

        var result = useCase.requestUpload(commandFor(workshop));

        var pending = brochures.pending.get(workshop.id());
        assertThat(pending).matches(workshop.id().value() + "/[0-9a-f-]{36}\\.pdf");
        assertThat(result.uploadUrl().toString()).contains(pending);
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(brochures.keys)
                .as("the brochure itself is not set until confirmation")
                .isEmpty();
    }

    @Test
    @DisplayName("issuing never calls the bucket, so it works while the bucket is down")
    void makesNoCallToTheBucket() {
        var workshop = existing(aPublishedWorkshop().build());
        storage.headFailure = InMemoryBrochureStorage.down();

        useCase.requestUpload(commandFor(workshop));

        assertThat(storage.headCalls).isZero();
        assertThat(brochures.pending).containsKey(workshop.id());
    }

    @Test
    @DisplayName("§5: issuing again replaces the pending key with a new one")
    void reissueReplaces() {
        var workshop = existing(aPublishedWorkshop().build());

        useCase.requestUpload(commandFor(workshop));
        var first = brochures.pending.get(workshop.id());
        useCase.requestUpload(commandFor(workshop));

        assertThat(brochures.pending.get(workshop.id())).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a draft may get a brochure (workshop.md §7: uploaded in the authoring session)")
    void draftIsAllowed() {
        var workshop = existing(aDraftWorkshop().build());

        useCase.requestUpload(commandFor(workshop));

        assertThat(brochures.pending).containsKey(workshop.id());
    }

    @Test
    @DisplayName("a cancelled or completed workshop gets no URL, and nothing is remembered")
    void terminalWorkshop() {
        for (var workshop :
                new Workshop[] {aCancelledWorkshop().build(), aCompletedWorkshop().build()}) {
            existing(workshop);

            assertThatThrownBy(() -> useCase.requestUpload(commandFor(workshop)))
                    .isInstanceOf(InvalidWorkshopStateException.class);
        }
        assertThat(brochures.pending).isEmpty();
    }

    @Test
    @DisplayName("a workshop that does not exist is not found")
    void unknownWorkshop() {
        var missing = WorkshopId.newId();
        when(workshops.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                useCase.requestUpload(
                                        new RequestBrochureUploadCommand(missing.value(), ACTOR)))
                .isInstanceOf(WorkshopNotFoundException.class);
        assertThat(brochures.pending).isEmpty();
    }

    @Test
    @DisplayName("a URL that cannot be computed is a storage failure, and no key is remembered")
    void presignFailure() {
        var workshop = existing(aPublishedWorkshop().build());
        storage.presignFailure = InMemoryBrochureStorage.down();

        assertThatThrownBy(() -> useCase.requestUpload(commandFor(workshop)))
                .isInstanceOf(BrochureStorageUnavailableException.class);
        assertThat(brochures.pending).isEmpty();
    }

    @Test
    @DisplayName("the issuance is audited, by the actor, against the workshop")
    void audited() {
        var workshop = existing(aPublishedWorkshop().build());

        useCase.requestUpload(commandFor(workshop));

        var captured = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captured.capture());
        assertThat(captured.getValue().action()).isEqualTo(AuditActions.BROCHURE_UPLOAD_ISSUED);
        assertThat(captured.getValue().targetId()).isEqualTo(workshop.id().value());
        assertThat(captured.getValue().actor()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("a refused request is not audited as issued")
    void notAuditedWhenRefused() {
        var workshop = existing(aCancelledWorkshop().build());

        assertThatThrownBy(() -> useCase.requestUpload(commandFor(workshop)));

        verify(audit, org.mockito.Mockito.never()).record(any());
    }
}
