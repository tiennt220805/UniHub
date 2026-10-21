package com.unihub.payment.application.usecase;

import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.query.GetPaymentQuery;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PaymentNotFoundException;
import com.unihub.registration.api.PayableRegistration;
import com.unihub.registration.api.PayableRegistrations;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetPaymentTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PayableRegistrations registrations = mock(PayableRegistrations.class);
    private final GetPayment get = new GetPayment(payments, registrations);

    private UserId ownerOf(com.unihub.payment.domain.model.Payment payment) {
        var owner = UserId.newId();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(registrations.find(payment.registrationId()))
                .thenReturn(
                        Optional.of(
                                new PayableRegistration(
                                        payment.registrationId(),
                                        WorkshopId.newId(),
                                        owner,
                                        true,
                                        Instant.parse("2026-12-01T09:15:00Z"))));
        return owner;
    }

    @Test
    @DisplayName("the owner of the registration sees the payment's current state")
    void owner_sees_it() {
        var payment = aPendingPayment().build();
        var owner = ownerOf(payment);

        var result = get.get(new GetPaymentQuery(owner, payment.id()));

        assertThat(result.id()).isEqualTo(payment.id().value());
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.resolvedAt()).isNull();
    }

    @Test
    @DisplayName("someone else is refused")
    void stranger_isRefused() {
        var payment = aPendingPayment().build();
        ownerOf(payment);

        assertThatThrownBy(() -> get.get(new GetPaymentQuery(UserId.newId(), payment.id())))
                .isInstanceOf(NotRegistrationOwnerException.class);
    }

    @Test
    @DisplayName("a payment whose registration cannot be found is refused to everyone")
    void orphan_isRefused() {
        var payment = aPendingPayment().build();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(registrations.find(payment.registrationId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> get.get(new GetPaymentQuery(UserId.newId(), payment.id())))
                .isInstanceOf(NotRegistrationOwnerException.class);
    }

    @Test
    @DisplayName("an unknown payment is not found")
    void unknown_isNotFound() {
        var id = PaymentId.newId();
        when(payments.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> get.get(new GetPaymentQuery(UserId.newId(), id)))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
