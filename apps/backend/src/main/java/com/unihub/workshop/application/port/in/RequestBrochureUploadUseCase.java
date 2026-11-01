package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.RequestBrochureUploadCommand;
import com.unihub.workshop.application.result.BrochureUploadResult;

public interface RequestBrochureUploadUseCase {

    /**
     * Issues a ten-minute presigned PUT for a fresh key and remembers the key. Makes no call to the
     * bucket, so it works while the bucket is down.
     *
     * @throws com.unihub.workshop.domain.exception.WorkshopNotFoundException no such workshop
     * @throws com.unihub.workshop.domain.exception.InvalidWorkshopStateException cancelled or
     *     completed
     */
    BrochureUploadResult requestUpload(RequestBrochureUploadCommand command);
}
