package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.workshop.application.command.ListManagedWorkshopsCriteria;
import com.unihub.workshop.application.port.in.ListManagedWorkshopsUseCase;
import com.unihub.workshop.application.port.out.ManagedWorkshopQueries;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import com.unihub.workshop.domain.exception.InvalidWorkshopSearchException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The management list reads the Master and skips the cache, for the reason {@code
 * GetWorkshopFromPrimary} gives: an Organizer who has just created or published a workshop must
 * find it in the next response, and the Replica or a 30s list entry may not have it yet. Whether
 * the caller may list at all is the controller's permission check ({@code WORKSHOP_EDIT}).
 */
@Service
class ListManagedWorkshops implements ListManagedWorkshopsUseCase {
    private final ManagedWorkshopQueries queries;

    ListManagedWorkshops(ManagedWorkshopQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public SearchWorkshopsResult list(ListManagedWorkshopsCriteria criteria) {
        if (criteria.status() != null) {
            try {
                WorkshopStatus.valueOf(criteria.status());
            } catch (IllegalArgumentException e) {
                throw new InvalidWorkshopSearchException("unknown status " + criteria.status());
            }
        }
        var title = criteria.titleContains() == null ? null : criteria.titleContains().strip();
        var normalised =
                new ListManagedWorkshopsCriteria(
                        criteria.status(),
                        title == null || title.isEmpty() ? null : title,
                        criteria.page(),
                        criteria.size());
        return SearchWorkshopsResult.of(queries.search(normalised));
    }
}
