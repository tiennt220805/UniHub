package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.CancelWorkshopCommand;
import com.unihub.workshop.application.result.WorkshopResult;

public interface CancelWorkshopUseCase {
    WorkshopResult cancel(CancelWorkshopCommand command);
}
