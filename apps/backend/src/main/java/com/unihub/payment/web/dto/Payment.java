package com.unihub.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * packages/api-contract {@code Payment}. Shares its simple name with the domain aggregate by design
 * (§10). The {@code resolvedAt ⇔ status ≠ PENDING} if/then/else is added by {@code
 * PaymentOpenApiConfig}. {@code createdAt}/{@code updatedAt} are optional in the contract and are
 * left out of the charge's snapshot rather than sent as {@code null}.
 */
@Schema(
        types = {"object"},
        requiredProperties = {
            "id",
            "registrationId",
            "amount",
            "currency",
            "gateway",
            "status",
            "initiatedAt"
        })
public record Payment(
        UUID id,
        UUID registrationId,
        @Schema(
                        types = {"number"},
                        format = "double",
                        exclusiveMinimumValue = 0)
                BigDecimal amount,
        String currency,
        String gateway,
        @Schema(types = {"string", "null"}) String gatewayTransactionId,
        PaymentStatus status,
        Instant initiatedAt,
        @Schema(types = {"string", "null"}) Instant resolvedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant updatedAt) {}
