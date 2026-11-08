package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.ConfirmCoverImageUploadCommand;
import com.unihub.workshop.application.result.CoverImageConfirmResult;

public interface ConfirmCoverImageUploadUseCase {
    CoverImageConfirmResult confirmUpload(ConfirmCoverImageUploadCommand command);
}
