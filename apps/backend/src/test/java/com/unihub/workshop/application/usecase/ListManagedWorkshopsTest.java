package com.unihub.workshop.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.workshop.application.command.ListManagedWorkshopsCriteria;
import com.unihub.workshop.application.port.out.ManagedWorkshopQueries;
import com.unihub.workshop.application.port.out.WorkshopCatalogEntry;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.domain.exception.InvalidWorkshopSearchException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ListManagedWorkshopsTest {

    private final ManagedWorkshopQueries queries = mock(ManagedWorkshopQueries.class);
    private final ListManagedWorkshops useCase = new ListManagedWorkshops(queries);

    private static WorkshopCatalogPage page(WorkshopCatalogEntry... entries) {
        return new WorkshopCatalogPage(List.of(entries), 0, 20, entries.length, 1);
    }

    @Test
    @DisplayName("trims the title filter and treats a blank one as absent before querying")
    void normalisesTitle() {
        when(queries.search(new ListManagedWorkshopsCriteria("DRAFT", "kube", 0, 20)))
                .thenReturn(page());
        when(queries.search(new ListManagedWorkshopsCriteria(null, null, 0, 20)))
                .thenReturn(page());

        useCase.list(new ListManagedWorkshopsCriteria("DRAFT", "  kube ", 0, 20));
        useCase.list(new ListManagedWorkshopsCriteria(null, "   ", 0, 20));

        verify(queries).search(new ListManagedWorkshopsCriteria("DRAFT", "kube", 0, 20));
        verify(queries).search(new ListManagedWorkshopsCriteria(null, null, 0, 20));
    }

    @Test
    @DisplayName("an unknown status is rejected before the database is asked")
    void rejectsUnknownStatus() {
        assertThatThrownBy(
                        () ->
                                useCase.list(
                                        new ListManagedWorkshopsCriteria("ARCHIVED", null, 0, 20)))
                .isInstanceOf(InvalidWorkshopSearchException.class);

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("maps a row of any status, with the cover as a path on our own origin")
    void mapsRows() {
        var id = UUID.randomUUID();
        var start = Instant.parse("2026-12-01T09:00:00Z");
        var entry =
                new WorkshopCatalogEntry(
                        id,
                        "Draft one",
                        "Room 1",
                        start,
                        "DRAFT",
                        "Ada",
                        start.plusSeconds(3600),
                        30,
                        0,
                        false,
                        null,
                        "covers/" + id + "/a.png");
        when(queries.search(new ListManagedWorkshopsCriteria(null, null, 0, 20)))
                .thenReturn(page(entry));

        var result = useCase.list(new ListManagedWorkshopsCriteria(null, null, 0, 20));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).status()).isEqualTo("DRAFT");
        assertThat(result.items().get(0).coverImageUrl())
                .isEqualTo("/media/covers/" + id + "/a.png");
        assertThat(result.totalElements()).isEqualTo(1);
    }
}
