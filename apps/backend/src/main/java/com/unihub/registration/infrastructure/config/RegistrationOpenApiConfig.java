package com.unihub.registration.infrastructure.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes, in the generated OpenAPI spec, the {@code holdExpiresAt ⇔ RESERVED} rule the contract
 * states with JSON Schema {@code if/then/else} (packages/api-contract/schemas/registration.yaml): a
 * {@code RESERVED} registration has a hold deadline, every other status has none. Bean Validation
 * cannot emit that; the rule itself is enforced by the {@code Registration} aggregate and the
 * table's CHECK. This only describes — contract-diff then proves it equals the contract's.
 */
@Configuration
class RegistrationOpenApiConfig {

    @Bean
    OpenApiCustomizer holdExpiresAtRule() {
        return openApi -> {
            var registration = openApi.getComponents().getSchemas().get("Registration");
            if (registration == null) {
                return;
            }
            var whenReserved = new JsonSchema();
            whenReserved.addProperty("status", constant("RESERVED"));

            var deadline = new JsonSchema();
            deadline.setTypes(Set.of("string"));
            var thenSchema = new JsonSchema();
            thenSchema.setRequired(List.of("holdExpiresAt"));
            thenSchema.addProperty("holdExpiresAt", deadline);

            var none = new JsonSchema();
            none.setTypes(Set.of("null"));
            var elseSchema = new JsonSchema();
            elseSchema.addProperty("holdExpiresAt", none);

            registration.setIf(whenReserved);
            registration.setThen(thenSchema);
            registration.setElse(elseSchema);
        };
    }

    private static Schema<?> constant(Object value) {
        var schema = new JsonSchema();
        schema.setConst(value);
        return schema;
    }
}
