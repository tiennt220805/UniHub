package com.unihub.workshop.application.usecase;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.port.out.WorkshopCatalogQueries;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import com.unihub.workshop.domain.exception.InvalidWorkshopSearchException;
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
        // dateFrom <= dateTo cannot be expressed across two query parameters in the contract
        // (paths/workshop.yaml, dateFrom), so it is enforced here.
        if (criteria.dateFrom() != null
                && criteria.dateTo() != null
                && criteria.dateFrom().isAfter(criteria.dateTo())) {
            throw new InvalidWorkshopSearchException("dateFrom must not be after dateTo");
        }
        return SearchWorkshopsResult.of(
                catalog.search(normalised(criteria))); // WorkshopCatalogQueries → Replica
    }

    private static SearchWorkshopsCriteria normalised(SearchWorkshopsCriteria c) {
        return new SearchWorkshopsCriteria(
                blankToNull(c.q()),
                blankToNull(c.speaker()),
                blankToNull(c.location()),
                c.dateFrom(),
                c.dateTo(),
                c.page(),
                c.size());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
