package com.unihub.payment.web.mapper;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.web.dto.CreatePaymentChargeRequest;
import com.unihub.payment.web.dto.Payment;
import com.unihub.payment.web.dto.PaymentStatus;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.security.Actor;

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
}
