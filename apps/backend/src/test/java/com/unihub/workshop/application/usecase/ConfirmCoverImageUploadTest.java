package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.command.ConfirmCoverImageUploadCommand;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.CoverImageStorage;
import com.unihub.workshop.application.port.out.CoverImageStorage.StoredCover;
import com.unihub.workshop.application.port.out.WorkshopCoverImages;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.CoverImageObjectMissingException;
import com.unihub.workshop.domain.exception.CoverImageRejectedException;
import com.unihub.workshop.domain.exception.CoverImageUploadNotIssuedException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfirmCoverImageUploadTest {
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final WorkshopCoverImages covers = mock(WorkshopCoverImages.class);
    private final CoverImageStorage storage = mock(CoverImageStorage.class);
    private final ConfirmCoverImageTransaction transaction =
            mock(ConfirmCoverImageTransaction.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final ConfirmCoverImageUpload useCase =
            new ConfirmCoverImageUpload(
                    workshops,
                    covers,
                    storage,
                    transaction,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final com.unihub.workshop.domain.model.Workshop workshop = aPublishedWorkshop().build();
    private final WorkshopId id = workshop.id();
    private final String pending = "covers/" + id.value() + "/abc.png";
    private final ConfirmCoverImageUploadCommand command =
            new ConfirmCoverImageUploadCommand(
                    id.value(), new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_EDIT")));

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
    };

    private void aPendingPng() {
        when(workshops.findById(id)).thenReturn(Optional.of(workshop));
        when(covers.pendingKey(id)).thenReturn(Optional.of(pending));
    }

    @Test
    @DisplayName(
            "a PNG stored as image/png within the limit becomes the cover, as a path on our origin")
    void accepts() {
        aPendingPng();
        when(storage.head(pending)).thenReturn(Optional.of(new StoredCover(1_000, "image/png")));
        when(storage.readStart(pending, 12)).thenReturn(PNG);
        when(transaction.persist(any(), anyString(), any())).thenReturn(true);

        var result = useCase.confirmUpload(command);

        assertThat(result.coverImageUrl()).isEqualTo("/media/" + pending);
        verify(transaction).persist(id, pending, command.actor());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName(
            "a file whose first bytes are not the issued format is deleted, forgotten and refused")
    void rejectsWrongBytes() {
        aPendingPng();
        when(storage.head(pending)).thenReturn(Optional.of(new StoredCover(1_000, "image/png")));
        when(storage.readStart(pending, 12)).thenReturn("<svg xmlns=\"x\"/>".getBytes());

        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(CoverImageRejectedException.class);

        verify(storage).delete(pending);
        verify(covers).discard(id, pending);
        verifyNoInteractions(transaction);
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditActions.COVER_IMAGE_UPLOAD_REJECTED);
    }

    @Test
    @DisplayName("too big, empty, or stored as another type is refused before a byte is read")
    void rejectsBySizeAndType() {
        aPendingPng();
        when(storage.head(pending))
                .thenReturn(Optional.of(new StoredCover(2_097_153, "image/png")))
                .thenReturn(Optional.of(new StoredCover(0, "image/png")))
                .thenReturn(Optional.of(new StoredCover(10, "image/jpeg")));

        for (var i = 0; i < 3; i++) {
            assertThatThrownBy(() -> useCase.confirmUpload(command))
                    .isInstanceOf(CoverImageRejectedException.class);
        }

        verify(storage, never()).readStart(anyString(), anyInt());
        verify(storage, org.mockito.Mockito.times(3)).delete(pending);
    }

    @Test
    @DisplayName("a rejected file whose deletion fails is still rejected and forgotten")
    void rejectionSurvivesAFailedDelete() {
        aPendingPng();
        when(storage.head(pending)).thenReturn(Optional.of(new StoredCover(0, "image/png")));
        org.mockito.Mockito.doThrow(new StorageUnavailableException("down", null))
                .when(storage)
                .delete(pending);

        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(CoverImageRejectedException.class);

        verify(covers).discard(id, pending);
    }

    @Test
    @DisplayName(
            "nothing at the key is a 404 that keeps it; an unreachable bucket is a 503 that keeps it")
    void missingAndUnavailable() {
        aPendingPng();
        when(storage.head(pending))
                .thenReturn(Optional.empty())
                .thenThrow(new StorageUnavailableException("down", null));

        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(CoverImageObjectMissingException.class);
        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(BrochureStorageUnavailableException.class);

        verify(covers, never()).discard(any(), anyString());
        verify(storage, never()).delete(anyString());
        verifyNoInteractions(transaction);
    }

    @Test
    @DisplayName("nothing issued, an unreadable key, or the loser of a race is a 409")
    void notIssued() {
        when(workshops.findById(id)).thenReturn(Optional.of(workshop));
        when(covers.pendingKey(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(CoverImageUploadNotIssuedException.class);

        when(covers.pendingKey(id)).thenReturn(Optional.of("covers/x/y.svg"));
        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(CoverImageUploadNotIssuedException.class);

        aPendingPng();
        when(storage.head(pending)).thenReturn(Optional.of(new StoredCover(10, "image/png")));
        when(storage.readStart(pending, 12)).thenReturn(PNG);
        when(transaction.persist(any(), anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(CoverImageUploadNotIssuedException.class);
    }

    @Test
    @DisplayName("an unknown workshop is a 404 and a finished one a 409, before storage is touched")
    void workshopState() {
        when(workshops.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(WorkshopNotFoundException.class);

        when(workshops.findById(id))
                .thenReturn(
                        Optional.of(
                                aPublishedWorkshop().withStatus(WorkshopStatus.CANCELLED).build()));
        assertThatThrownBy(() -> useCase.confirmUpload(command))
                .isInstanceOf(InvalidWorkshopStateException.class);

        verifyNoInteractions(storage);
    }
}
