package com.unihub.workshop.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCatalogEntry;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopCatalogQueries;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchWorkshopsTest {

    private final WorkshopCatalogQueries catalog = mock(WorkshopCatalogQueries.class);
    private final SearchWorkshops useCase = new SearchWorkshops(catalog);

    @Test
    @DisplayName(
            "maps the catalog page returned by WorkshopCatalogQueries into a SearchWorkshopsResult")
    void search_delegatesToCatalogAndMapsResult() {
        var criteria = new SearchWorkshopsCriteria(null, null, null, null, null, 0, 20);
        var workshopId = UUID.randomUUID();
        var startTime = Instant.parse("2026-01-01T00:00:00Z");
        var entry =
                new WorkshopCatalogEntry(
                        workshopId, "Intro to Kubernetes", "Room 101", startTime, "PUBLISHED");
        var page = new WorkshopCatalogPage(List.of(entry), 0, 20, 1L, 1);
        when(catalog.search(criteria)).thenReturn(page);

        SearchWorkshopsResult result = useCase.search(criteria);

        assertThat(result.items()).hasSize(1);
        SearchWorkshopsResult.Item item = result.items().get(0);
        assertThat(item.id()).isEqualTo(workshopId);
        assertThat(item.title()).isEqualTo("Intro to Kubernetes");
        assertThat(item.location()).isEqualTo("Room 101");
        assertThat(item.startTime()).isEqualTo(startTime);
        assertThat(item.status()).isEqualTo("PUBLISHED");
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns an empty result when the catalog page has no matching workshops")
    void search_returnsEmptyResult_whenCatalogPageIsEmpty() {
        var criteria = new SearchWorkshopsCriteria(null, null, null, null, null, 0, 20);
        var page = new WorkshopCatalogPage(List.of(), 0, 20, 0L, 0);
        when(catalog.search(criteria)).thenReturn(page);

        SearchWorkshopsResult result = useCase.search(criteria);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }
}
