package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.RequestBrochureUploadCommand;
import com.unihub.workshop.application.port.in.RequestBrochureUploadUseCase;
import com.unihub.workshop.application.port.out.BrochureStorage;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.BrochureUploadResult;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * workshop.md §7, issuance. The key is generated here and remembered in {@code
 * pending_brochure_key}; {@code brochure_s3_key} is not touched until the upload is confirmed. The
 * URL is computed locally, so this works with the bucket down. Not transactional: the workshop is
 * read, then one plain statement writes the key.
 */
@Service
class RequestBrochureUpload implements RequestBrochureUploadUseCase {
    /**
     * workshop.md §7: long enough for 10 MB on an ordinary connection, short enough to leak safely.
     */
    static final Duration TTL = Duration.ofMinutes(10);

    private final WorkshopRepository workshops;
    private final WorkshopBrochures brochures;
    private final BrochureStorage storage;
    private final AuditTrail auditTrail;
    private final Clock clock;

    RequestBrochureUpload(
            WorkshopRepository workshops,
            WorkshopBrochures brochures,
            BrochureStorage storage,
            AuditTrail auditTrail,
            Clock clock) {
        this.workshops = workshops;
        this.brochures = brochures;
        this.storage = storage;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public BrochureUploadResult requestUpload(RequestBrochureUploadCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        if (workshop.status().isTerminal()) {
            throw new InvalidWorkshopStateException(
                    id, workshop.status(), "upload a brochure for it");
        }

        var key = storage.newKey(id);
        BrochureStorage.PresignedUpload upload;
        try {
            upload = storage.presignUpload(key, TTL);
        } catch (StorageUnavailableException e) {
            throw new BrochureStorageUnavailableException("could not issue an upload URL", e);
        }
        brochures.issue(id, key);
        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.BROCHURE_UPLOAD_ISSUED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        clock.instant()));
        return new BrochureUploadResult(upload.url(), upload.expiresAt());
    }
}
