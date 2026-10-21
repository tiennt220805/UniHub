package com.unihub.registration.application.usecase;

import static com.unihub.registration.domain.model.RegistrationBuilder.aCancelledRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aConfirmedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationPayabilityTest {
    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final RegistrationPayability payability = new RegistrationPayability(repository);

    @Test
    @DisplayName("a RESERVED registration is payable, with its owner, workshop and hold end")
    void reserved() {
        var registration = aReservedRegistration().build();
        when(repository.findById(registration.id())).thenReturn(Optional.of(registration));

        var found = payability.find(registration.id()).orElseThrow();

        assertThat(found.reserved()).isTrue();
        assertThat(found.userId()).isEqualTo(registration.userId());
        assertThat(found.workshopId()).isEqualTo(registration.workshopId());
        assertThat(found.holdExpiresAt()).isEqualTo(registration.holdExpiresAt());
    }

    @Test
    @DisplayName("a confirmed or cancelled registration is found but not payable")
    void notReserved() {
        for (var registration :
                new com.unihub.registration.domain.model.Registration[] {
                    aConfirmedRegistration().build(), aCancelledRegistration().build()
                }) {
            when(repository.findById(registration.id())).thenReturn(Optional.of(registration));

            var found = payability.find(registration.id()).orElseThrow();

            assertThat(found.reserved()).as(registration.status().name()).isFalse();
            assertThat(found.holdExpiresAt()).isNull();
        }
    }

    @Test
    @DisplayName("an unknown registration is empty")
    void unknown() {
        when(repository.findById(RegistrationId.newId())).thenReturn(Optional.empty());

        assertThat(payability.find(RegistrationId.newId())).isEmpty();
    }
}
