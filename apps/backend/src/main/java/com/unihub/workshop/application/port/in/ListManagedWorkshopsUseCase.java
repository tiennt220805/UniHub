package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.ListManagedWorkshopsCriteria;
import com.unihub.workshop.application.result.SearchWorkshopsResult;

/** The workshops of every status, for the people who manage them (packages/api-contract). */
public interface ListManagedWorkshopsUseCase {
    SearchWorkshopsResult list(ListManagedWorkshopsCriteria criteria);
}
