package com.unihub.payment.application.command;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.security.Actor;
import java.util.Objects;

/**
 * @param actor the authenticated caller (JWT), never a value from the request body
 * @param paymentMethodToken opaque token from the gateway's client SDK, never card data
 * @param idempotencyKey the {@code Idempotency-Key} header's UUID, canonical lowercase form
 * @param requestFingerprint SHA-256 of the canonical request, to tell a retry from a reuse
 */
public record ChargePaymentCommand(
        Actor.UserActor actor,
        RegistrationId registrationId,
        String paymentMethodToken,
        String idempotencyKey,
        String requestFingerprint) {

    public ChargePaymentCommand {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(registrationId);
        Objects.requireNonNull(paymentMethodToken);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(requestFingerprint);
    }
}
