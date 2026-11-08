package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.RequestCoverImageUploadCommand;
import com.unihub.workshop.application.port.in.RequestCoverImageUploadUseCase;
import com.unihub.workshop.application.port.out.BrochureStorage.PresignedUpload;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.CoverImageStorage;
import com.unihub.workshop.application.port.out.WorkshopCoverImages;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.CoverImageUploadResult;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.InvalidCoverImageRequestException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.CoverImageFormat;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * workshop.md §7 for the cover image (Phase 8, D120-D122), issuance. The key is generated here and
 * remembered in {@code pending_cover_image_key}; {@code cover_image_key} is not touched until the
 * upload is confirmed. The URL is signed for the declared type and the exact size, so a bucket that
 * enforces the signature cannot receive anything else. Computed locally, so it works with the
 * bucket down. Not transactional: one read, then one plain statement.
 */
@Service
class RequestCoverImageUpload implements RequestCoverImageUploadUseCase {
    static final Duration TTL = Duration.ofMinutes(10);

    private final WorkshopRepository workshops;
    private final WorkshopCoverImages covers;
    private final CoverImageStorage storage;
    private final AuditTrail auditTrail;
    private final Clock clock;

    RequestCoverImageUpload(
            WorkshopRepository workshops,
            WorkshopCoverImages covers,
            CoverImageStorage storage,
            AuditTrail auditTrail,
            Clock clock) {
        this.workshops = workshops;
        this.covers = covers;
        this.storage = storage;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public CoverImageUploadResult requestUpload(RequestCoverImageUploadCommand command) {
        var format =
                CoverImageFormat.ofContentType(command.contentType())
                        .orElseThrow(
                                () ->
                                        new InvalidCoverImageRequestException(
                                                "contentType must be image/jpeg, image/png or image/webp"));
        if (command.contentLength() < 1 || command.contentLength() > CoverImageFormat.MAX_BYTES) {
            throw new InvalidCoverImageRequestException(
                    "contentLength must be between 1 and " + CoverImageFormat.MAX_BYTES + " bytes");
        }
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        if (workshop.status().isTerminal()) {
            throw new InvalidWorkshopStateException(
                    id, workshop.status(), "upload a cover image for it");
        }

        var key = storage.newKey(id, format);
        PresignedUpload upload;
        try {
            upload = storage.presignUpload(key, format, command.contentLength(), TTL);
        } catch (StorageUnavailableException e) {
            throw new BrochureStorageUnavailableException("could not issue an upload URL", e);
        }
        covers.issue(id, key);
        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.COVER_IMAGE_UPLOAD_ISSUED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        clock.instant()));
        return new CoverImageUploadResult(upload.url(), upload.expiresAt());
    }
}
