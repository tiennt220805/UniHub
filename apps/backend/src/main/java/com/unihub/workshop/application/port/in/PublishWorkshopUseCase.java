package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.PublishWorkshopCommand;
import com.unihub.workshop.application.result.WorkshopResult;

public interface PublishWorkshopUseCase {
    WorkshopResult publish(PublishWorkshopCommand command);
}
