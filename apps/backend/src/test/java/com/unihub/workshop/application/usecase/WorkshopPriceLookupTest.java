package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkshopPriceLookupTest {
    private final WorkshopRepository repository = mock(WorkshopRepository.class);
    private final WorkshopPriceLookup lookup = new WorkshopPriceLookup(repository);

    @Test
    @DisplayName("a paid workshop has its price")
    void paid() {
        var workshop = aPaidWorkshop().build();
        when(repository.findById(workshop.id())).thenReturn(Optional.of(workshop));

        assertThat(lookup.priceOf(workshop.id())).contains(workshop.price());
    }

    @Test
    @DisplayName("a free workshop has none, and neither does an unknown one")
    void freeOrUnknown() {
        var free = aPublishedWorkshop().build();
        when(repository.findById(free.id())).thenReturn(Optional.of(free));
        var unknown = WorkshopId.newId();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(lookup.priceOf(free.id())).isEmpty();
        assertThat(lookup.priceOf(unknown)).isEmpty();
    }
}
