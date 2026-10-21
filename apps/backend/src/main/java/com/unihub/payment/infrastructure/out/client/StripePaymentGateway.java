package com.unihub.payment.infrastructure.out.client;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentMethodRejectedException;
import com.unihub.shared.domain.id.PaymentId;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * {@link PaymentGateway} over Stripe, sandbox only (payment.md §3, checklist D16). The only class
 * that touches the SDK, and the only caller of the gateway.
 *
 * <p>Every call carries a Stripe idempotency key fixed by payment and by step ({@code
 * pi-create:{id}}, {@code pi-confirm:{id}}), so a retry of a step by anyone is the same request to
 * Stripe. The client is built per instance, with the SDK's own retries off and both timeouts set
 * explicitly (the SDK's defaults depend on its version).
 */
final class StripePaymentGateway implements PaymentGateway {
    private final StripeClient stripe;

    StripePaymentGateway(StripeProperties properties) {
        this.stripe =
                StripeClient.builder()
                        .setApiKey(properties.secretKey())
                        .setApiBase(properties.apiBase())
                        .setConnectTimeout((int) properties.connectTimeout().toMillis())
                        .setReadTimeout((int) properties.readTimeout().toMillis())
                        .setMaxNetworkRetries(0)
                        .setHttpClient(
                                new JdkStripeHttpClient(
                                        properties.connectTimeout(), properties.readTimeout()))
                        .build();
    }

    @Override
    public String create(
            PaymentId paymentId, BigDecimal amount, String currency, String paymentMethodToken) {
        var params =
                PaymentIntentCreateParams.builder()
                        .setAmount(StripeAmounts.minorUnits(amount, currency))
                        .setCurrency(currency.toLowerCase(Locale.ROOT))
                        .setPaymentMethod(paymentMethodToken)
                        // Explicit: the money moves in confirm, never here.
                        .setConfirm(false)
                        // For an operator investigating; no code resolves a payment from it.
                        .putMetadata("payment_id", paymentId.value().toString())
                        // Cards only: with redirects allowed, confirm would demand a return_url.
                        .setAutomaticPaymentMethods(
                                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                        .setEnabled(true)
                                        .setAllowRedirects(
                                                PaymentIntentCreateParams.AutomaticPaymentMethods
                                                        .AllowRedirects.NEVER)
                                        .build())
                        .build();
        try {
            var id =
                    stripe.v1()
                            .paymentIntents()
                            .create(params, options("pi-create", paymentId))
                            .getId();
            if (id == null || id.isBlank()) {
                throw new GatewayCallFailedException("the gateway returned no id", null);
            }
            return id;
        } catch (InvalidRequestException e) {
            // Only a complaint about the payment method itself is the caller's input; any other
            // invalid request (amount, currency) is ours, hence a failure of the call.
            if ("payment_method".equals(e.getParam())) {
                throw new PaymentMethodRejectedException("the payment method was refused", e);
            }
            throw new GatewayCallFailedException("creating the payment failed", e);
        } catch (StripeException | RuntimeException e) {
            // RuntimeException too: the SDK itself fails on an error answer without a body.
            throw new GatewayCallFailedException("creating the payment failed", e);
        }
    }

    @Override
    public ConfirmOutcome confirm(PaymentId paymentId, String gatewayTransactionId) {
        var params = PaymentIntentConfirmParams.builder().setErrorOnRequiresAction(true).build();
        PaymentIntent intent;
        try {
            intent =
                    stripe.v1()
                            .paymentIntents()
                            .confirm(
                                    gatewayTransactionId, params, options("pi-confirm", paymentId));
        } catch (CardException e) {
            // A declined card is an answer: Stripe replies 402 and the SDK throws.
            return new ConfirmOutcome.Declined(reasonOf(e));
        } catch (ApiConnectionException e) {
            if (GatewayFailures.provablyNotSent(e)) {
                throw new GatewayNotReachedException("the confirm never left UniHub", e);
            }
            throw new GatewayOutcomeUnknownException("the confirm may have been sent", e);
        } catch (StripeException e) {
            // Any other answer (5xx, 4xx that is not a decline, rate limit, a key conflict): the
            // request reached Stripe and says nothing usable. Never "not reached".
            throw new GatewayOutcomeUnknownException("the confirm got no usable answer", e);
        } catch (RuntimeException e) {
            // The SDK failing on the answer itself (it throws on an error reply without a body):
            // the request was sent, so this is never "not reached".
            throw new GatewayOutcomeUnknownException("the confirm answer could not be read", e);
        }
        return outcomeOf(intent);
    }

    private static ConfirmOutcome outcomeOf(PaymentIntent intent) {
        var status = intent.getStatus();
        if (status == null) {
            throw new GatewayOutcomeUnknownException("the confirm answered without a status", null);
        }
        return switch (status) {
            case "succeeded" -> new ConfirmOutcome.Succeeded();
            case "processing" -> new ConfirmOutcome.Processing();
            case "requires_payment_method" ->
                    new ConfirmOutcome.Declined(
                            intent.getLastPaymentError() == null
                                    ? "requires_payment_method"
                                    : String.valueOf(intent.getLastPaymentError().getCode()));
            // requires_action cannot happen (error_on_requires_action), and nothing else is
            // expected
            // after a confirm: leave it PENDING for reconciliation, which knows every status.
            default ->
                    throw new GatewayOutcomeUnknownException(
                            "the confirm answered with status " + status, null);
        };
    }

    private static String reasonOf(CardException e) {
        if (e.getDeclineCode() != null) {
            return e.getDeclineCode();
        }
        return e.getCode() == null ? "card_declined" : e.getCode();
    }

    private static RequestOptions options(String step, PaymentId paymentId) {
        return RequestOptions.builder().setIdempotencyKey(step + ":" + paymentId.value()).build();
    }
}
