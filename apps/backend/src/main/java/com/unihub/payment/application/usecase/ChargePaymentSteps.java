package com.unihub.payment.application.usecase;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.event.PaymentOutboxTranslator;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PayableRegistrationNotFoundException;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.registration.api.PayableRegistrations;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.usecase.IdempotentReplay;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.workshop.api.WorkshopPricing;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions around the gateway calls of {@link ChargePayment}
 * (backend-coding-standards .md §19.4). A separate bean so every call crosses the Spring proxy; no
 * transaction is ever open while the gateway is being called.
 */
@Service
class ChargePaymentSteps {
    private static final IdempotencyScope SCOPE = IdempotencyScope.PAYMENT_CHARGE;

    /** How the gateway's answer settles the payment in {@link #resolve}. */
    enum Resolution {
        SUCCEEDED,
        FAILED,
        /** The confirm was sent and the outcome is not known: the payment stays {@code PENDING}. */
        INITIATED
    }

    sealed interface Begun permits Started, Decided {}

    /** The payment row exists and the gateway may be called. */
    record Started(PaymentId paymentId, BigDecimal amount, String currency) implements Begun {}

    /** The request was settled without the gateway: a replay, or a recorded rejection. */
    record Decided(ChargePaymentResult result) implements Begun {}

    private final IdempotencyStore idempotency;
    private final PayableRegistrations registrations;
    private final WorkshopPricing pricing;
    private final PaymentRepository payments;
    private final EventPublisher events;
    private final Clock clock;

    ChargePaymentSteps(
            IdempotencyStore idempotency,
            PayableRegistrations registrations,
            WorkshopPricing pricing,
            PaymentRepository payments,
            EventPublisher events,
            Clock clock) {
        this.idempotency = idempotency;
        this.registrations = registrations;
        this.pricing = pricing;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    /**
     * tx1: the key and the {@code PENDING} payment commit together, or a rejection is recorded
     * under the key. The registration is read before the first write, which is the key.
     *
     * @throws com.unihub.payment.domain.exception.LivePaymentExistsException when another request
     *     won the partial unique index; nothing of this transaction is kept
     */
    @Transactional
    Begun begin(ChargePaymentCommand command) {
        var userId = command.actor().userId();
        // Backstop re-check: a duplicate may have committed while this request was on its way.
        var replay =
                IdempotentReplay.of(
                        idempotency,
                        SCOPE,
                        userId,
                        command.idempotencyKey(),
                        command.requestFingerprint(),
                        ChargePaymentResult.class);
        if (replay.isPresent()) {
            return new Decided(replay.get());
        }

        var registration =
                registrations
                        .find(command.registrationId())
                        .orElseThrow(
                                () ->
                                        new PayableRegistrationNotFoundException(
                                                command.registrationId()));
        if (!registration.userId().equals(userId)) {
            throw new NotRegistrationOwnerException(command.registrationId());
        }
        var now = clock.instant();
        if (!registration.reserved() || !now.isBefore(registration.holdExpiresAt())) {
            return record(command, new ChargePaymentResult.RegistrationNotReserved());
        }
        if (payments.existsLiveFor(command.registrationId())) {
            return record(command, new ChargePaymentResult.PaymentInProgress());
        }

        var amount =
                pricing.priceOf(registration.workshopId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "RESERVED registration "
                                                        + command.registrationId().value()
                                                        + " is for a workshop with no price"));
        var paymentId = PaymentId.newId();
        // The first write of the transaction (§21 rule 2); the payment id is the guarded resource.
        idempotency.begin(
                SCOPE,
                userId,
                command.idempotencyKey(),
                command.requestFingerprint(),
                paymentId.value());
        payments.save(
                Payment.initiate(
                        paymentId,
                        command.registrationId(),
                        amount,
                        Payment.DEFAULT_CURRENCY,
                        now));
        return new Started(paymentId, amount, Payment.DEFAULT_CURRENCY);
    }

    /**
     * Two requests with different keys raced for the same registration and this one lost the index:
     * a decided domain failure, recorded under its own key in a transaction of its own (the one
     * that lost is gone, and a constraint violation leaves nothing to write into).
     */
    @Transactional
    Decided recordInProgress(ChargePaymentCommand command) {
        var replay =
                IdempotentReplay.of(
                        idempotency,
                        SCOPE,
                        command.actor().userId(),
                        command.idempotencyKey(),
                        command.requestFingerprint(),
                        ChargePaymentResult.class);
        if (replay.isPresent()) {
            return new Decided(replay.get());
        }
        return record(command, new ChargePaymentResult.PaymentInProgress());
    }

    /** The gateway created its object: keep its id before any confirm can be sent. */
    @Transactional
    void attach(PaymentId paymentId, String gatewayTransactionId) {
        var payment = load(paymentId);
        payment.attachGatewayTransactionId(gatewayTransactionId);
        payments.save(payment);
    }

    /**
     * tx2: settles the payment with what the gateway said and completes the key with the snapshot.
     * A success writes its outbox row in this same transaction (payment.md §9, D10). A payment that
     * is no longer {@code PENDING} (the webhook got there first) is left as it is; the snapshot
     * then shows that.
     */
    @Transactional
    ChargePaymentResult resolve(
            ChargePaymentCommand command, PaymentId paymentId, Resolution resolution) {
        var payment = load(paymentId);
        var settled = payment;
        if (payment.status() == PaymentStatus.PENDING && resolution != Resolution.INITIATED) {
            var now = clock.instant();
            if (resolution == Resolution.SUCCEEDED) {
                payment.succeed(now);
            } else {
                payment.fail(now);
            }
            var recorded = payment.pullEvents();
            settled = payments.save(payment);
            recorded.stream()
                    .flatMap(event -> PaymentOutboxTranslator.translate(event).stream())
                    .forEach(events::publish);
        }
        var result = ChargePaymentResult.Processed.of(settled);
        idempotency.complete(
                SCOPE, command.actor().userId(), command.idempotencyKey(), result, null);
        return result;
    }

    /**
     * Compensation (checklist G1b, D16): provably no money moved, so the payment is cancelled and
     * the key deleted, and a same-key retry executes for real.
     */
    @Transactional
    void abandon(PaymentId paymentId) {
        var payment = load(paymentId);
        if (payment.status() == PaymentStatus.PENDING) {
            payment.cancel(clock.instant());
            payments.save(payment);
        }
        idempotency.abandonOpenFor(SCOPE, paymentId.value());
    }

    private Decided record(ChargePaymentCommand command, ChargePaymentResult result) {
        var userId = command.actor().userId();
        idempotency.begin(
                SCOPE, userId, command.idempotencyKey(), command.requestFingerprint(), null);
        idempotency.complete(SCOPE, userId, command.idempotencyKey(), result, null);
        return new Decided(result);
    }

    private Payment load(PaymentId paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "payment " + paymentId.value() + " vanished"));
    }
}
