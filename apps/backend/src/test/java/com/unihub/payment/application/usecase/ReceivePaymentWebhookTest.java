package com.unihub.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.application.port.out.PaymentWebhookVerifier;
import com.unihub.payment.application.usecase.PaymentWebhookTransaction.Effect;
import com.unihub.payment.domain.exception.InvalidWebhookSignatureException;
import com.unihub.shared.domain.OptimisticConflictException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReceivePaymentWebhookTest {
    private static final GatewayPaymentEvent EVENT =
            new GatewayPaymentEvent("pi_1", GatewayPaymentEvent.Outcome.SUCCEEDED);

    private final PaymentWebhookVerifier verifier = mock(PaymentWebhookVerifier.class);
    private final PaymentWebhookTransaction transaction = mock(PaymentWebhookTransaction.class);
    private final ReceivePaymentWebhook receive = new ReceivePaymentWebhook(verifier, transaction);

    @Test
    @DisplayName("a webhook that does not verify reaches no transaction")
    void invalidSignature_touchesNothing() {
        when(verifier.verify("body", "sig")).thenThrow(new InvalidWebhookSignatureException());

        assertThatThrownBy(() -> receive.receive("body", "sig"))
                .isInstanceOf(InvalidWebhookSignatureException.class);

        verifyNoInteractions(transaction);
    }

    @Test
    @DisplayName("a verified event about nothing we act on is acknowledged without a transaction")
    void unrelated_isAcknowledged() {
        when(verifier.verify("body", "sig")).thenReturn(Optional.empty());

        receive.receive("body", "sig");

        verifyNoInteractions(transaction);
    }

    @Test
    @DisplayName("a lost version race is retried in a new transaction")
    void lostRace_isRetried() {
        when(verifier.verify("body", "sig")).thenReturn(Optional.of(EVENT));
        when(transaction.apply(EVENT))
                .thenThrow(new OptimisticConflictException("PAYMENT", UUID.randomUUID()))
                .thenReturn(Effect.RESOLVED);

        receive.receive("body", "sig");

        verify(transaction, times(2)).apply(EVENT);
    }

    @Test
    @DisplayName(
            "when every attempt loses, the webhook is NOT acknowledged, so the gateway redelivers")
    void exhausted_isNotAcknowledged() {
        when(verifier.verify("body", "sig")).thenReturn(Optional.of(EVENT));
        when(transaction.apply(any()))
                .thenThrow(new OptimisticConflictException("PAYMENT", UUID.randomUUID()));

        assertThatThrownBy(() -> receive.receive("body", "sig"))
                .isInstanceOf(OptimisticConflictException.class);

        verify(transaction, times(3)).apply(EVENT);
    }
}
