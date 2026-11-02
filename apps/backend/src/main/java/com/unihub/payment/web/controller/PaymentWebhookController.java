package com.unihub.payment.web.controller;

import com.unihub.payment.application.port.in.ReceivePaymentWebhookUseCase;
import com.unihub.shared.web.ApiProblems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code handlePaymentGatewayWebhook} (packages/api-contract/paths/payment.yaml). Called by the
 * gateway, so there is no JWT and no permission: trust is the signature. The body is taken as raw
 * bytes and handed over untouched, because the signature is over exactly those bytes; nothing here
 * parses it. Anything but an invalid signature is acknowledged with {@code 200}.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class PaymentWebhookController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final ReceivePaymentWebhookUseCase receive;

    PaymentWebhookController(ReceivePaymentWebhookUseCase receive) {
        this.receive = receive;
    }

    @Operation(
            operationId = "handlePaymentGatewayWebhook",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-auth-model",
                                            value = "gateway-webhook-signature")),
            tags = {"Payment"})
    @SecurityRequirements
    @ApiResponse(
            responseCode = "200",
            headers =
                    @Header(
                            name = "X-Correlation-Id",
                            schema =
                                    @Schema(
                                            types = {"string"},
                                            pattern = CORRELATION_ID_PATTERN)))
    @ApiProblems.BadRequest
    @PostMapping(path = "/payment-gateway", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> handle(
            @Parameter(
                            name = "Stripe-Signature",
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(types = {"string"}))
                    @RequestHeader(name = "Stripe-Signature", required = false)
                    String signature,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(types = {"object"})))
                    @RequestBody
                    byte[] payload) {
        receive.receive(new String(payload, StandardCharsets.UTF_8), signature);
        return ResponseEntity.ok().build();
    }
}
