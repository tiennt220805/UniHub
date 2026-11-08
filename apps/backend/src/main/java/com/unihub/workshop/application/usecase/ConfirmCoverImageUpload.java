package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.ConfirmCoverImageUploadCommand;
import com.unihub.workshop.application.port.in.ConfirmCoverImageUploadUseCase;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.CoverImageStorage;
import com.unihub.workshop.application.port.out.WorkshopCoverImages;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.CoverImageConfirmResult;
import com.unihub.workshop.application.result.CoverImages;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.CoverImageObjectMissingException;
import com.unihub.workshop.domain.exception.CoverImageRejectedException;
import com.unihub.workshop.domain.exception.CoverImageUploadNotIssuedException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.CoverImageFormat;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * workshop.md §7 for the cover image (Phase 8, D120-D122), confirmation, in the order that gives
 * every guard its own answer: nothing issued (409) → bucket unreachable (503, pending key kept) →
 * object absent (404, pending key kept) → object not an acceptable cover (422: the object is
 * deleted and the key cleared) → one transaction that only counts if it changed the row (409 for
 * the loser of a race). What is checked is what arrived, never what the client said: the size and
 * {@code Content-Type} the bucket stored, the format the issued key was made for, and the object's
 * first bytes. Not transactional itself: the calls to the bucket must not hold a transaction.
 */
@Service
class ConfirmCoverImageUpload implements ConfirmCoverImageUploadUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(ConfirmCoverImageUpload.class);

    private final WorkshopRepository workshops;
    private final WorkshopCoverImages covers;
    private final CoverImageStorage storage;
    private final ConfirmCoverImageTransaction transaction;
    private final AuditTrail auditTrail;
    private final Clock clock;

    ConfirmCoverImageUpload(
            WorkshopRepository workshops,
            WorkshopCoverImages covers,
            CoverImageStorage storage,
            ConfirmCoverImageTransaction transaction,
            AuditTrail auditTrail,
            Clock clock) {
        this.workshops = workshops;
        this.covers = covers;
        this.storage = storage;
        this.transaction = transaction;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public CoverImageConfirmResult confirmUpload(ConfirmCoverImageUploadCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        if (workshop.status().isTerminal()) {
            throw new InvalidWorkshopStateException(
                    id, workshop.status(), "confirm a cover image upload for it");
        }
        var pending =
                covers.pendingKey(id).orElseThrow(() -> new CoverImageUploadNotIssuedException(id));
        // The key was made by this server for one format; anything else on the row is corrupt.
        var format =
                CoverImageFormat.ofKey(pending)
                        .orElseThrow(() -> new CoverImageUploadNotIssuedException(id));

        String rejection;
        try {
            rejection = inspect(id, pending, format);
        } catch (StorageUnavailableException e) {
            throw new BrochureStorageUnavailableException(
                    "object storage cannot be reached to confirm the upload", e);
        }
        if (rejection != null) {
            reject(id, pending, command, rejection);
            throw new CoverImageRejectedException(id, rejection);
        }

        if (!transaction.persist(id, pending, command.actor())) {
            throw new CoverImageUploadNotIssuedException(id);
        }
        return new CoverImageConfirmResult(CoverImages.urlOf(pending));
    }

    /**
     * @return why the object is not acceptable, or null when it is
     */
    private String inspect(WorkshopId id, String key, CoverImageFormat format) {
        var stored = storage.head(key).orElseThrow(() -> new CoverImageObjectMissingException(id));
        if (stored.contentLength() < 1 || stored.contentLength() > CoverImageFormat.MAX_BYTES) {
            return "the file is empty or larger than " + CoverImageFormat.MAX_BYTES + " bytes";
        }
        if (!format.contentType().equalsIgnoreCase(stored.contentType())) {
            return "the file was stored as "
                    + stored.contentType()
                    + ", not "
                    + format.contentType();
        }
        var head = storage.readStart(key, CoverImageFormat.SIGNATURE_BYTES);
        if (!format.matches(head)) {
            return "the file is not a " + format.contentType() + " image";
        }
        return null;
    }

    /** A rejected object is never served: delete it and forget its key. */
    private void reject(
            WorkshopId id, String key, ConfirmCoverImageUploadCommand command, String reason) {
        try {
            storage.delete(key);
        } catch (StorageUnavailableException e) {
            // Already rejecting; the cover row never points at it and the read policy and the
            // pending key keep it unreachable. An orphan, the same accepted waste as a replaced
            // file.
            LOG.warn(
                    "could not delete the rejected cover {} of workshop {}: {}",
                    key,
                    id,
                    e.toString());
        }
        covers.discard(id, key);
        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.COVER_IMAGE_UPLOAD_REJECTED,
                        AuditTargets.WORKSHOP,
                        id.value(),
                        clock.instant()));
        LOG.info("rejected the cover of workshop {}: {}", id, reason);
    }
}
