package com.unihub.workshop.application.usecase;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopCatalogQueries;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import com.unihub.workshop.domain.exception.InvalidWorkshopSearchException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SearchWorkshops implements SearchWorkshopsUseCase {
    private final WorkshopCatalogQueries catalog;
    private final WorkshopCache cache;

    SearchWorkshops(WorkshopCatalogQueries catalog, WorkshopCache cache) {
        this.catalog = catalog;
        this.cache = cache;
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
        var normalised = normalised(criteria);
        // Cached 30s per normalised query and never invalidated by a write (workshop.md §2).
        var cached = cache.findList(normalised);
        if (cached.isPresent()) {
            return SearchWorkshopsResult.of(cached.get());
        }
        var page = catalog.search(normalised); // WorkshopCatalogQueries → Replica
        cache.putList(normalised, page);
        return SearchWorkshopsResult.of(page);
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
