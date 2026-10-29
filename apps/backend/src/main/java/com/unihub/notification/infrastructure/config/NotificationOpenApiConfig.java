package com.unihub.notification.infrastructure.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes, in the generated OpenAPI spec, the {@code sentAt ⇔ SENT} rule the contract states with
 * JSON Schema {@code if/then/else} (packages/api-contract/schemas/notification.yaml): a {@code
 * SENT} notification has a {@code sentAt}, every other status has none. Bean Validation cannot emit
 * that; the rule itself is kept by the conditional {@code UPDATE} that sets both together. This
 * only describes, and contract-diff then proves it equals the contract's.
 */
@Configuration
class NotificationOpenApiConfig {

    @Bean
    OpenApiCustomizer sentAtRule() {
        return openApi -> {
            var notification = openApi.getComponents().getSchemas().get("Notification");
            if (notification == null) {
                return;
            }
            var whenSent = new JsonSchema();
            whenSent.addProperty("status", constant("SENT"));

            var instant = new JsonSchema();
            instant.setTypes(Set.of("string"));
            var thenSchema = new JsonSchema();
            thenSchema.setRequired(List.of("sentAt"));
            thenSchema.addProperty("sentAt", instant);

            var none = new JsonSchema();
            none.setTypes(Set.of("null"));
            var elseSchema = new JsonSchema();
            elseSchema.addProperty("sentAt", none);

            notification.setIf(whenSent);
            notification.setThen(thenSchema);
            notification.setElse(elseSchema);
        };
    }

    private static Schema<?> constant(Object value) {
        var schema = new JsonSchema();
        schema.setConst(value);
        return schema;
    }
}
