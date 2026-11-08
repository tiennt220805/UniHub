package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.RequestCoverImageUploadCommand;
import com.unihub.workshop.application.result.CoverImageUploadResult;

public interface RequestCoverImageUploadUseCase {
    CoverImageUploadResult requestUpload(RequestCoverImageUploadCommand command);
}
