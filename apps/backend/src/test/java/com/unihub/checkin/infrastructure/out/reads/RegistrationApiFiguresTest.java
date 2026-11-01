package com.unihub.checkin.infrastructure.out.reads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.checkin.application.port.out.RegistrationTotals;
import com.unihub.registration.api.RegistrationCounts;
import com.unihub.registration.api.WorkshopRegistrations;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Translation only: Registration's counts and ids become Check-in's, unchanged. */
class RegistrationApiFiguresTest {
    private final WorkshopRegistrations registrations = mock(WorkshopRegistrations.class);
    private final RegistrationApiFigures figures = new RegistrationApiFigures(registrations);

    @Test
    @DisplayName("the counts and the ids cross the boundary as they are")
    void translates() {
        var workshop = WorkshopId.newId();
        var ids = List.of(RegistrationId.newId(), RegistrationId.newId());
        when(registrations.countsOf(workshop)).thenReturn(new RegistrationCounts(3, 1));
        when(registrations.idsOf(workshop)).thenReturn(ids);

        assertThat(figures.totalsOf(workshop)).isEqualTo(new RegistrationTotals(3, 1));
        assertThat(figures.idsOf(workshop)).isEqualTo(ids);
    }
}
