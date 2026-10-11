package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.result.WorkshopResult;

public interface GetWorkshopUseCase {
    WorkshopResult get(GetWorkshopQuery query);
}
