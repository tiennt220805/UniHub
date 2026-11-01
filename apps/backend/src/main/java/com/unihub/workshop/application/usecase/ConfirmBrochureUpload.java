package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.ConfirmBrochureUploadCommand;
import com.unihub.workshop.application.port.in.ConfirmBrochureUploadUseCase;
import com.unihub.workshop.application.port.out.BrochureStorage;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.BrochureObjectMissingException;
import com.unihub.workshop.domain.exception.BrochureStorageUnavailableException;
import com.unihub.workshop.domain.exception.BrochureUploadNotIssuedException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import org.springframework.stereotype.Service;

/**
 * workshop.md §7, confirmation, in the order that gives every guard its own answer: nothing issued
 * (409) → bucket unreachable (503, pending key kept) → object absent (404, pending key kept) → one
 * transaction that only counts if it changed the row (409 for the loser of a race). Not
 * transactional itself: the call to the bucket must not hold a database transaction.
 */
@Service
class ConfirmBrochureUpload implements ConfirmBrochureUploadUseCase {
    private final WorkshopRepository workshops;
    private final WorkshopBrochures brochures;
    private final BrochureStorage storage;
    private final ConfirmBrochureTransaction transaction;

    ConfirmBrochureUpload(
            WorkshopRepository workshops,
            WorkshopBrochures brochures,
            BrochureStorage storage,
            ConfirmBrochureTransaction transaction) {
        this.workshops = workshops;
        this.brochures = brochures;
        this.storage = storage;
        this.transaction = transaction;
    }

    @Override
    public void confirmUpload(ConfirmBrochureUploadCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        if (workshop.status().isTerminal()) {
            throw new InvalidWorkshopStateException(
                    id, workshop.status(), "confirm a brochure upload for it");
        }
        var pending =
                brochures
                        .pendingKey(id)
                        .orElseThrow(() -> new BrochureUploadNotIssuedException(id));

        try {
            if (storage.head(pending).isEmpty()) {
                throw new BrochureObjectMissingException(id);
            }
        } catch (StorageUnavailableException e) {
            throw new BrochureStorageUnavailableException(
                    "object storage cannot be reached to confirm the upload", e);
        }

        if (!transaction.persist(id, pending, command.actor())) {
            throw new BrochureUploadNotIssuedException(id);
        }
    }
}
