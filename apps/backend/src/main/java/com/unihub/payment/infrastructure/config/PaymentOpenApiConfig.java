package com.unihub.payment.infrastructure.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes, in the generated OpenAPI spec, the {@code resolvedAt ⇔ status ≠ PENDING} rule and the
 * amount's {@code exclusiveMinimum: 0} the contract states with JSON Schema {@code if/then/else}
 * (packages/api-contract/schemas/payment.yaml): a {@code PENDING} payment has no resolution time,
 * every other status has one. Bean Validation cannot emit that; the rule itself is enforced by the
 * {@code Payment} aggregate. This only describes — contract-diff then proves it equals the
 * contract's.
 */
@Configuration
class PaymentOpenApiConfig {

    @Bean
    OpenApiCustomizer resolvedAtRule() {
        return openApi -> {
            var payment = openApi.getComponents().getSchemas().get("Payment");
            if (payment == null) {
                return;
            }
            // exclusiveMinimum: 0 — the annotation cannot express a zero bound.
            var amount = (Schema<?>) payment.getProperties().get("amount");
            if (amount != null) {
                amount.setExclusiveMinimumValue(BigDecimal.ZERO);
            }
            var whenPending = new JsonSchema();
            whenPending.addProperty("status", constant("PENDING"));

            var none = new JsonSchema();
            none.setTypes(Set.of("null"));
            var thenSchema = new JsonSchema();
            thenSchema.addProperty("resolvedAt", none);

            var instant = new JsonSchema();
            instant.setTypes(Set.of("string"));
            var elseSchema = new JsonSchema();
            elseSchema.setRequired(List.of("resolvedAt"));
            elseSchema.addProperty("resolvedAt", instant);

            payment.setIf(whenPending);
            payment.setThen(thenSchema);
            payment.setElse(elseSchema);
        };
    }

    private static Schema<?> constant(Object value) {
        var schema = new JsonSchema();
        schema.setConst(value);
        return schema;
    }
}
