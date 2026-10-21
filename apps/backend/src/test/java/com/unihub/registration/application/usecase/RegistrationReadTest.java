package com.unihub.registration.application.usecase;

import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.port.out.RegistrationPage;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.query.GetRegistrationQuery;
import com.unihub.registration.application.query.ListMyRegistrationsQuery;
import com.unihub.registration.domain.exception.RegistrationNotFoundException;
import com.unihub.registration.domain.exception.RegistrationNotOwnedException;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationReadTest {
    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final GetRegistration get = new GetRegistration(registrations);
    private final ListMyRegistrations list = new ListMyRegistrations(registrations);

    @Test
    @DisplayName("the owner sees the registration, hold deadline included")
    void owner_sees_it() {
        var registration = aReservedRegistration().build();
        when(registrations.findById(registration.id())).thenReturn(Optional.of(registration));

        var result = get.get(new GetRegistrationQuery(registration.userId(), registration.id()));

        assertThat(result.id()).isEqualTo(registration.id().value());
        assertThat(result.status()).isEqualTo("RESERVED");
        assertThat(result.holdExpiresAt()).isEqualTo(registration.holdExpiresAt());
    }

    @Test
    @DisplayName("someone else is refused, and the refusal names nobody")
    void stranger_isRefused() {
        var registration = aReservedRegistration().build();
        when(registrations.findById(registration.id())).thenReturn(Optional.of(registration));
        var stranger = UserId.newId();

        assertThatThrownBy(() -> get.get(new GetRegistrationQuery(stranger, registration.id())))
                .isInstanceOf(RegistrationNotOwnedException.class)
                .hasMessageNotContaining(registration.userId().value().toString());
    }

    @Test
    @DisplayName("an unknown registration is not found")
    void unknown_isNotFound() {
        var id = RegistrationId.newId();
        when(registrations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> get.get(new GetRegistrationQuery(UserId.newId(), id)))
                .isInstanceOf(RegistrationNotFoundException.class);
    }

    @Test
    @DisplayName("the list is the caller's, filtered by status, and carries the page")
    void list_isScopedAndPaged() {
        var registration = aReservedRegistration().build();
        var caller = registration.userId();
        when(registrations.findByUser(caller, RegistrationStatus.RESERVED, 2, 5))
                .thenReturn(new RegistrationPage(List.of(registration), 2, 5, 11, 3));

        var result = list.list(new ListMyRegistrationsQuery(caller, "RESERVED", 2, 5));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).status()).isEqualTo("RESERVED");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("no status means every status")
    void list_noStatus() {
        var caller = UserId.newId();
        when(registrations.findByUser(caller, null, 0, 20))
                .thenReturn(new RegistrationPage(List.of(), 0, 20, 0, 0));

        list.list(new ListMyRegistrationsQuery(caller, null, 0, 20));

        verify(registrations).findByUser(caller, null, 0, 20);
    }

    @Test
    @DisplayName("a negative page or an empty size is refused by the query itself")
    void query_refusesBadPaging() {
        var caller = UserId.newId();

        assertThatThrownBy(() -> new ListMyRegistrationsQuery(caller, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListMyRegistrationsQuery(caller, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
