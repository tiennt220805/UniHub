package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.command.ConfirmBrochureUploadCommand;
import com.unihub.workshop.application.event.BrochureSummarizationRequested;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.BrochureObjectMissingException;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.BrochureUploadNotIssuedException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.Workshop;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * workshop.md §7, confirmation, guard by guard (Phase 5 checklist 9.2, M-50, D60): what each
 * refusal answers, that the pending key survives the refusals that are retryable, and that the
 * summarization request is written only by the confirmation that changed the row.
 */
class ConfirmBrochureUploadTest {
    private static final Actor ACTOR = new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_EDIT"));
    private static final Instant NOW = Instant.parse("2026-11-01T08:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final InMemoryWorkshopBrochures brochures = new InMemoryWorkshopBrochures();
    private final InMemoryBrochureStorage storage = new InMemoryBrochureStorage(clock);
    private final List<OutboxMessage> published = new ArrayList<>();
    private final EventPublisher events = published::add;
    private final AuditTrail audit = mock(AuditTrail.class);
    private final InMemoryWorkshopCache cache = new InMemoryWorkshopCache();
    private final ConfirmBrochureUpload useCase =
            new ConfirmBrochureUpload(
                    workshops,
                    brochures,
                    storage,
                    new ConfirmBrochureTransaction(brochures, events, audit, cache, clock));

    private Workshop workshop = aPublishedWorkshop().build();
    private final String key = workshop.id().value() + "/6f1c.pdf";

    private ConfirmBrochureUploadCommand confirm() {
        return new ConfirmBrochureUploadCommand(workshop.id().value(), ACTOR);
    }

    private void issuedAndUploaded() {
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        brochures.pending.put(workshop.id(), key);
        storage.objects.put(key, 1_234L);
    }

    @Test
    @DisplayName(
            "an uploaded object becomes the brochure, the pending key is cleared, one request is written")
    void confirms() {
        issuedAndUploaded();

        useCase.confirmUpload(confirm());

        assertThat(brochures.keys).containsEntry(workshop.id(), key);
        assertThat(brochures.pending).isEmpty();
        assertThat(published).hasSize(1);
        var request = (BrochureSummarizationRequested) published.getFirst();
        assertThat(request.workshopId()).isEqualTo(workshop.id().value());
        assertThat(request.brochureKey()).isEqualTo(key);
        assertThat(request.occurredAt()).isEqualTo(NOW);
        assertThat(request.routingKey()).isEqualTo("unihub.workshop.summarization.requested");
        assertThat(cache.invalidated).containsExactly(workshop.id().value());
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditActions.BROCHURE_UPLOAD_CONFIRMED);
    }

    @Test
    @DisplayName("nothing issued is a conflict, decided before the bucket is asked anything")
    void nothingIssued() {
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));

        assertThatThrownBy(() -> useCase.confirmUpload(confirm()))
                .isInstanceOf(BrochureUploadNotIssuedException.class);
        assertThat(storage.headCalls).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName(
            "a bucket that cannot be reached is a 503 that writes nothing and keeps the pending key")
    void bucketDown() {
        issuedAndUploaded();
        storage.headFailure = InMemoryBrochureStorage.down();

        assertThatThrownBy(() -> useCase.confirmUpload(confirm()))
                .isInstanceOf(BrochureStorageUnavailableException.class);
        assertThat(brochures.pending).containsEntry(workshop.id(), key);
        assertThat(brochures.keys).isEmpty();
        assertThat(published).isEmpty();
        assertThat(cache.invalidated).isEmpty();

        // storage is back: the very same call now succeeds
        storage.headFailure = null;
        useCase.confirmUpload(confirm());
        assertThat(brochures.keys).containsEntry(workshop.id(), key);
        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName(
            "an object that is not there yet is a 404 that writes nothing and keeps the pending key")
    void notUploadedYet() {
        issuedAndUploaded();
        storage.objects.clear();

        assertThatThrownBy(() -> useCase.confirmUpload(confirm()))
                .isInstanceOf(BrochureObjectMissingException.class);
        assertThat(brochures.pending).containsEntry(workshop.id(), key);
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("losing the race for the pending key is a conflict and writes no request")
    void lostTheRace() {
        issuedAndUploaded();
        // another confirmation took the key between the bucket check and this transaction
        var racing =
                new WorkshopBrochures() {
                    @Override
                    public Optional<String> currentKey(WorkshopId id) {
                        return brochures.currentKey(id);
                    }

                    @Override
                    public boolean recordSummary(WorkshopId id, String k, String s) {
                        return brochures.recordSummary(id, k, s);
                    }

                    @Override
                    public void issue(WorkshopId id, String k) {
                        brochures.issue(id, k);
                    }

                    @Override
                    public Optional<String> pendingKey(WorkshopId id) {
                        return brochures.pendingKey(id);
                    }

                    @Override
                    public boolean confirm(WorkshopId id, String k) {
                        brochures.confirm(id, k); // the other confirmation, first
                        return brochures.confirm(id, k); // ours: no longer matches
                    }
                };
        var racingUseCase =
                new ConfirmBrochureUpload(
                        workshops,
                        racing,
                        storage,
                        new ConfirmBrochureTransaction(racing, events, audit, cache, clock));

        assertThatThrownBy(() -> racingUseCase.confirmUpload(confirm()))
                .isInstanceOf(BrochureUploadNotIssuedException.class);
        assertThat(published).as("no second paid AI call").isEmpty();
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName(
            "confirming twice: the second finds nothing issued, and only one request was ever written")
    void secondConfirm() {
        issuedAndUploaded();
        useCase.confirmUpload(confirm());

        assertThatThrownBy(() -> useCase.confirmUpload(confirm()))
                .isInstanceOf(BrochureUploadNotIssuedException.class);
        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("a workshop that does not exist is not found; a cancelled one is a conflict")
    void workshopChecks() {
        var missing = WorkshopId.newId();
        when(workshops.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                useCase.confirmUpload(
                                        new ConfirmBrochureUploadCommand(missing.value(), ACTOR)))
                .isInstanceOf(WorkshopNotFoundException.class);

        workshop = aCancelledWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        brochures.pending.put(workshop.id(), key);
        storage.objects.put(key, 1L);
        assertThatThrownBy(() -> useCase.confirmUpload(confirm()))
                .isInstanceOf(InvalidWorkshopStateException.class);
        assertThat(published).isEmpty();
    }
}
