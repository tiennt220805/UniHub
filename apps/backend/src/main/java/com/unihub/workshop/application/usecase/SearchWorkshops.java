package com.unihub.workshop.application.usecase;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.port.out.WorkshopCatalogQueries;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SearchWorkshops implements SearchWorkshopsUseCase {
    private final WorkshopCatalogQueries catalog;

    SearchWorkshops(WorkshopCatalogQueries catalog) {
        this.catalog = catalog;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchWorkshopsResult search(SearchWorkshopsCriteria criteria) {
        return SearchWorkshopsResult.of(
                catalog.search(criteria)); // WorkshopCatalogQueries → Replica
    }
}
