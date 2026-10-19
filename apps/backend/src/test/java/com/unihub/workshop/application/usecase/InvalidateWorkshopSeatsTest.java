package com.unihub.workshop.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvalidateWorkshopSeatsTest {

    private final InMemoryWorkshopCache cache = new InMemoryWorkshopCache();
    private final InvalidateWorkshopSeats invalidation = new InvalidateWorkshopSeats(cache);

    @Test
    @DisplayName("another module's seat change drops the seats entry only, through Workshop's api")
    void seatsChanged_dropsTheSeatsEntryOnly() {
        var id = UUID.randomUUID();

        invalidation.seatsChanged(id);

        assertThat(cache.seatsInvalidated).containsExactly(id);
        assertThat(cache.invalidated).as("the slow-changing detail is untouched").isEmpty();
    }
}
