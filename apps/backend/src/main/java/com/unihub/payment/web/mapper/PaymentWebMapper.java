package com.unihub.payment.web.mapper;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.query.GetPaymentQuery;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.application.result.PaymentResult;
import com.unihub.payment.web.dto.CreatePaymentChargeRequest;
import com.unihub.payment.web.dto.Payment;
import com.unihub.payment.web.dto.PaymentStatus;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.security.Actor;
import java.util.UUID;

public final class PaymentWebMapper {

    private PaymentWebMapper() {}

    public static ChargePaymentCommand toCommand(
            CreatePaymentChargeRequest request,
            String idempotencyKey,
            String requestFingerprint,
            Actor.UserActor actor) {
        return new ChargePaymentCommand(
                actor,
                RegistrationId.of(request.registrationId()),
                request.paymentMethodToken(),
                idempotencyKey,
                requestFingerprint);
    }

    /** The 201 body: the snapshot the charge step recorded, not the payment's current state. */
    public static Payment toResponse(ChargePaymentResult.Processed processed) {
        return new Payment(
                processed.paymentId(),
                processed.registrationId(),
                processed.amount(),
                processed.currency(),
                processed.gateway(),
                processed.gatewayTransactionId(),
                PaymentStatus.valueOf(processed.status().name()),
                processed.initiatedAt(),
                processed.resolvedAt(),
                null,
                null);
    }

    public static GetPaymentQuery toQuery(UUID id, Actor.UserActor caller) {
        return new GetPaymentQuery(caller.userId(), PaymentId.of(id));
    }

    /** The current state of a payment, the source of truth the charge's snapshot defers to. */
    public static Payment toResponse(PaymentResult r) {
        return new Payment(
                r.id(),
                r.registrationId(),
                r.amount(),
                r.currency(),
                r.gateway(),
                r.gatewayTransactionId(),
                PaymentStatus.valueOf(r.status()),
                r.initiatedAt(),
                r.resolvedAt(),
                null,
                null);
    }
}
