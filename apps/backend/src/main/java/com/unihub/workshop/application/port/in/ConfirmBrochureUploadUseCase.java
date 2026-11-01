package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.ConfirmBrochureUploadCommand;

public interface ConfirmBrochureUploadUseCase {

    /**
     * Checks with the bucket that the issued key holds an object, then, in one transaction, makes
     * it the workshop's brochure and writes the summarization request.
     *
     * @throws com.unihub.workshop.domain.exception.WorkshopNotFoundException no such workshop
     * @throws com.unihub.workshop.domain.exception.InvalidWorkshopStateException cancelled or
     *     completed
     * @throws com.unihub.workshop.domain.exception.BrochureUploadNotIssuedException nothing to
     *     confirm, or already confirmed
     * @throws com.unihub.workshop.domain.exception.BrochureStorageUnavailableException the bucket
     *     could not be reached; nothing persisted, the pending key kept
     * @throws com.unihub.workshop.domain.exception.BrochureObjectMissingException nothing uploaded
     *     at the issued key yet; nothing persisted, the pending key kept
     */
    void confirmUpload(ConfirmBrochureUploadCommand command);
}
