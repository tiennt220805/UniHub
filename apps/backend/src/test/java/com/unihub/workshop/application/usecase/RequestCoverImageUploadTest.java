package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.command.RequestCoverImageUploadCommand;
import com.unihub.workshop.application.port.out.BrochureStorage.PresignedUpload;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.CoverImageStorage;
import com.unihub.workshop.application.port.out.WorkshopCoverImages;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.InvalidCoverImageRequestException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.CoverImageFormat;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestCoverImageUploadTest {
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final WorkshopCoverImages covers = mock(WorkshopCoverImages.class);
    private final CoverImageStorage storage = mock(CoverImageStorage.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final RequestCoverImageUpload useCase =
            new RequestCoverImageUpload(
                    workshops, covers, storage, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    private final com.unihub.workshop.domain.model.Workshop workshop = aPublishedWorkshop().build();

    private RequestCoverImageUploadCommand command(String type, long length) {
        return new RequestCoverImageUploadCommand(
                workshop.id().value(),
                type,
                length,
                new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_EDIT")));
    }

    @Test
    @DisplayName(
            "signs the declared type and size, remembers the key and audits, whatever the bucket's state")
    void issues() {
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        var key = "covers/" + workshop.id().value() + "/k.webp";
        when(storage.newKey(workshop.id(), CoverImageFormat.WEBP)).thenReturn(key);
        when(storage.presignUpload(eq(key), eq(CoverImageFormat.WEBP), eq(2_048L), any()))
                .thenReturn(new PresignedUpload(URI.create("http://x/y"), NOW.plusSeconds(600)));

        var result = useCase.requestUpload(command("image/webp", 2_048));

        assertThat(result.uploadUrl()).isEqualTo(URI.create("http://x/y"));
        verify(storage).presignUpload(key, CoverImageFormat.WEBP, 2_048L, Duration.ofMinutes(10));
        verify(covers).issue(workshop.id(), key);
        verify(audit).record(any());
    }

    @Test
    @DisplayName("refuses an SVG, a GIF, an unknown type and any size outside 1 … 2 MiB")
    void refusesBadRequests() {
        for (var type : new String[] {"image/svg+xml", "image/gif", "application/pdf", null}) {
            assertThatThrownBy(() -> useCase.requestUpload(command(type, 100)))
                    .isInstanceOf(InvalidCoverImageRequestException.class);
        }
        for (var length : new long[] {0, -1, 2_097_153}) {
            assertThatThrownBy(() -> useCase.requestUpload(command("image/png", length)))
                    .isInstanceOf(InvalidCoverImageRequestException.class);
        }

        verifyNoInteractions(workshops, storage, covers);
    }

    @Test
    @DisplayName("an unknown workshop is a 404 and a finished one a 409; nothing is issued")
    void workshopState() {
        when(workshops.findById(workshop.id())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.requestUpload(command("image/png", 10)))
                .isInstanceOf(WorkshopNotFoundException.class);

        when(workshops.findById(workshop.id()))
                .thenReturn(
                        Optional.of(
                                aPublishedWorkshop().withStatus(WorkshopStatus.COMPLETED).build()));
        assertThatThrownBy(() -> useCase.requestUpload(command("image/png", 10)))
                .isInstanceOf(InvalidWorkshopStateException.class);

        verifyNoInteractions(storage, covers);
    }

    @Test
    @DisplayName("a presigner that fails is a 503 and nothing is remembered")
    void presignerFails() {
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        when(storage.newKey(any(), any())).thenReturn("covers/a/b.png");
        when(storage.presignUpload(any(), any(), anyLong(), any()))
                .thenThrow(new StorageUnavailableException("no", null));

        assertThatThrownBy(() -> useCase.requestUpload(command("image/png", 10)))
                .isInstanceOf(BrochureStorageUnavailableException.class);

        verifyNoInteractions(covers);
    }
}
