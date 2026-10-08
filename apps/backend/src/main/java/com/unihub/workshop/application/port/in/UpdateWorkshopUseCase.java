package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.result.WorkshopResult;

public interface UpdateWorkshopUseCase {
    WorkshopResult update(UpdateWorkshopCommand command);
}
