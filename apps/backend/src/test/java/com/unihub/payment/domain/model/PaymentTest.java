package com.unihub.payment.domain.model;

import static com.unihub.payment.domain.model.PaymentBuilder.RESOLVED_AT;
import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static com.unihub.payment.domain.model.PaymentBuilder.aResolvedPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.payment.domain.event.PaymentSucceeded;
import com.unihub.payment.domain.event.RefundRequired;
import com.unihub.payment.domain.exception.InvalidPaymentStateException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** State transition catalog for {@code payments.status} (test-design-techniques.md §2). */
class PaymentTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:02:00Z");

    @Test
    @DisplayName(
            "a new payment is PENDING at the initial version, with no gateway id or resolution")
    void initiate_isPending() {
        var payment =
                Payment.initiate(
                        PaymentId.newId(),
                        RegistrationId.newId(),
                        new BigDecimal("100000.00"),
                        "VND",
                        NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.gateway()).isEqualTo("STRIPE");
        assertThat(payment.initiatedAt()).isEqualTo(NOW);
        assertThat(payment.resolvedAt()).isNull();
        assertThat(payment.gatewayTransactionId()).isNull();
        assertThat(payment.version()).isEqualTo(Version.initial());
    }

    @Test
    @DisplayName("the gateway's id is stored once, while the payment is still PENDING")
    void attachGatewayTransactionId_once() {
        var payment = aPendingPayment().build();

        payment.attachGatewayTransactionId("pi_123");

        assertThat(payment.gatewayTransactionId()).isEqualTo("pi_123");
        assertThatThrownBy(() -> payment.attachGatewayTransactionId("pi_other"))
                .as("a second id would orphan the first PaymentIntent")
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(payment.gatewayTransactionId()).isEqualTo("pi_123");
    }

    @Test
    @DisplayName("a resolved payment takes no gateway id, and a blank id is refused")
    void attachGatewayTransactionId_rules() {
        var resolved = aResolvedPayment(PaymentStatus.FAILED).build();
        var pending = aPendingPayment().build();

        assertThatThrownBy(() -> resolved.attachGatewayTransactionId("pi_1"))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> pending.attachGatewayTransactionId(" "))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> pending.attachGatewayTransactionId(null))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("succeeding records PaymentSucceeded, once; failing and cancelling record nothing")
    void succeed_recordsTheEvent() {
        var succeeded = aPendingPayment().build();
        var failed = aPendingPayment().build();
        var cancelled = aPendingPayment().build();

        succeeded.succeed(NOW);
        failed.fail(NOW);
        cancelled.cancel(NOW);

        assertThat(succeeded.pullEvents())
                .containsExactly(
                        new PaymentSucceeded(succeeded.id(), succeeded.registrationId(), NOW));
        assertThat(succeeded.pullEvents()).isEmpty();
        assertThat(failed.pullEvents()).isEmpty();
        assertThat(cancelled.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("a refused success records nothing, so it can never confirm a registration")
    void refusedSuccess_recordsNothing() {
        var resolved = aResolvedPayment(PaymentStatus.CANCELLED).build();

        assertThatThrownBy(() -> resolved.succeed(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(resolved.pullEvents()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"FAILED", "CANCELLED"})
    @DisplayName(
            "a late success for a FAILED or CANCELLED payment goes straight to REFUND_REQUIRED")
    void lateSuccess_goesStraightToRefundRequired(PaymentStatus closed) {
        var payment = aResolvedPayment(closed).build();

        payment.lateSuccess(NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        assertThat(payment.resolvedAt()).as("it was resolved before, and still is").isNotNull();
        assertThat(payment.pullEvents())
                .as(
                        "never through SUCCEEDED, so it can never confirm a registration: the refund job")
                .containsExactly(new RefundRequired(payment.id(), payment.registrationId(), NOW));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"PENDING", "SUCCEEDED", "REFUND_REQUIRED", "REFUNDED"})
    @DisplayName("a late success applies only to a closed payment: every other status refuses it")
    void lateSuccess_refusedElsewhere(PaymentStatus status) {
        var payment =
                status == PaymentStatus.PENDING
                        ? aPendingPayment().build()
                        : aResolvedPayment(status).build();

        assertThatThrownBy(() -> payment.lateSuccess(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("PENDING → SUCCEEDED / FAILED / CANCELLED each stamp resolved_at")
    void pending_resolvesToAnyTerminal() {
        var succeeded = aPendingPayment().build();
        var failed = aPendingPayment().build();
        var cancelled = aPendingPayment().build();

        succeeded.succeed(NOW);
        failed.fail(NOW);
        cancelled.cancel(NOW);

        assertThat(succeeded.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(cancelled.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(succeeded.resolvedAt()).isEqualTo(NOW);
        assertThat(failed.resolvedAt()).isEqualTo(NOW);
        assertThat(cancelled.resolvedAt()).isEqualTo(NOW);
    }

    static Stream<BiConsumer<Payment, Instant>> resolutions() {
        return Stream.of(Payment::succeed, Payment::fail, Payment::cancel);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"SUCCEEDED", "FAILED", "CANCELLED", "REFUND_REQUIRED", "REFUNDED"})
    @DisplayName("a resolved payment rejects every resolution and keeps its status and resolved_at")
    void resolvedPayment_rejectsEveryResolution(PaymentStatus status) {
        resolutions()
                .forEach(
                        resolution -> {
                            var payment = aResolvedPayment(status).build();

                            assertThatThrownBy(() -> resolution.accept(payment, NOW))
                                    .isInstanceOf(InvalidPaymentStateException.class);
                            assertThat(payment.status()).isEqualTo(status);
                            assertThat(payment.resolvedAt()).isEqualTo(RESOLVED_AT);
                        });
    }

    @Test
    @DisplayName("a PENDING payment with a resolved_at is not a valid row")
    void restore_pendingCannotBeResolved() {
        assertThatThrownBy(() -> aPendingPayment().withResolvedAt(RESOLVED_AT).build())
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("every value of the status CHECK can be restored (including the refund statuses)")
    void restore_everyStatus(PaymentStatus status) {
        var resolvedAt = status == PaymentStatus.PENDING ? null : RESOLVED_AT;

        var payment = aPendingPayment().withStatus(status).withResolvedAt(resolvedAt).build();

        assertThat(payment.status()).isEqualTo(status);
    }

    @Test
    @DisplayName("only PENDING and SUCCEEDED are live (uq_payments_live_per_registration)")
    void liveStatuses() {
        assertThat(PaymentStatus.PENDING.isLive()).isTrue();
        assertThat(PaymentStatus.SUCCEEDED.isLive()).isTrue();
        assertThat(PaymentStatus.FAILED.isLive()).isFalse();
        assertThat(PaymentStatus.CANCELLED.isLive()).isFalse();
        assertThat(PaymentStatus.REFUND_REQUIRED.isLive()).isFalse();
        assertThat(PaymentStatus.REFUNDED.isLive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.00", "10000000000.00", "1.001"})
    @DisplayName("an amount must be positive and fit NUMERIC(12,2)")
    void amount_isValidated(String amount) {
        assertThatThrownBy(() -> aPendingPayment().withAmount(new BigDecimal(amount)).build())
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("a missing amount is rejected")
    void amount_required() {
        assertThatThrownBy(() -> aPendingPayment().withAmount(null).build())
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VN", "VNDD", ""})
    @DisplayName("a currency is a 3-letter code")
    void currency_isValidated(String currency) {
        assertThatThrownBy(() -> aPendingPayment().withCurrency(currency).build())
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName("a missing currency is rejected")
    void currency_required() {
        assertThatThrownBy(() -> aPendingPayment().withCurrency(null).build())
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    @DisplayName(
            "a SUCCEEDED payment whose registration cannot be confirmed becomes REFUND_REQUIRED")
    void requireRefund_fromSucceeded() {
        var payment = aResolvedPayment(PaymentStatus.SUCCEEDED).build();

        payment.requireRefund(NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        assertThat(payment.resolvedAt())
                .as("it was resolved when it succeeded; the refund duty does not re-stamp it")
                .isEqualTo(RESOLVED_AT);
        assertThat(payment.pullEvents())
                .as("the refund job is the only thing that follows")
                .containsExactly(new RefundRequired(payment.id(), payment.registrationId(), NOW));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"PENDING", "FAILED", "CANCELLED", "REFUND_REQUIRED", "REFUNDED"})
    @DisplayName(
            "only a SUCCEEDED payment can require a refund: no money, or already on its way, is refused")
    void requireRefund_refusedElsewhere(PaymentStatus status) {
        var payment =
                status == PaymentStatus.PENDING
                        ? aPendingPayment().build()
                        : aResolvedPayment(status).build();

        assertThatThrownBy(() -> payment.requireRefund(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(payment.status()).isEqualTo(status);
    }

    @Test
    @DisplayName("a REFUND_REQUIRED payment becomes REFUNDED when the gateway reports the refund")
    void refund_fromRefundRequired() {
        var payment = aResolvedPayment(PaymentStatus.REFUND_REQUIRED).build();

        payment.refund(NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.pullEvents()).as("nothing follows a refund").isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"PENDING", "SUCCEEDED", "FAILED", "CANCELLED", "REFUNDED"})
    @DisplayName("only a REFUND_REQUIRED payment can be refunded: REFUNDED -> * is refused too")
    void refund_refusedElsewhere(PaymentStatus status) {
        var payment =
                status == PaymentStatus.PENDING
                        ? aPendingPayment().build()
                        : aResolvedPayment(status).build();

        assertThatThrownBy(() -> payment.refund(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(payment.status()).isEqualTo(status);
    }

    @Test
    @DisplayName("REFUNDED is final: no transition leaves it")
    void refunded_isFinal() {
        var payment = aResolvedPayment(PaymentStatus.REFUNDED).build();

        assertThatThrownBy(() -> payment.succeed(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> payment.fail(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> payment.cancel(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> payment.lateSuccess(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> payment.requireRefund(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> payment.refund(NOW))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
    }
}
