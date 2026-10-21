package com.unihub.payment.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * packages/api-contract {@code CreatePaymentChargeRequest}. Nothing here names the payer: the
 * caller comes from the token. {@code paymentMethodToken} is an opaque token from the gateway's
 * client SDK, never card data.
 */
@Schema(
        types = {"object"},
        requiredProperties = {"registrationId", "paymentMethodToken"})
public record CreatePaymentChargeRequest(
        @NotNull UUID registrationId, @NotBlank String paymentMethodToken) {}
