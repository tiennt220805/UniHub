package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.CreateWorkshopCommand;
import com.unihub.workshop.application.result.WorkshopResult;

public interface CreateWorkshopUseCase {
    WorkshopResult create(CreateWorkshopCommand command);
}
