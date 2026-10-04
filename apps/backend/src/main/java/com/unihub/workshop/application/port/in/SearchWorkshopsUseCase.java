package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.result.SearchWorkshopsResult;

public interface SearchWorkshopsUseCase {
    SearchWorkshopsResult search(SearchWorkshopsCriteria criteria);
}
