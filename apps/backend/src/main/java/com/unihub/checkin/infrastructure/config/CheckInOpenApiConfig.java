package com.unihub.checkin.infrastructure.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes, in the generated OpenAPI spec, how {@code ScanEvent.ticketCode} depends on {@code
 * deviceVerdict}, which the contract states with JSON Schema {@code if/then/else}
 * (packages/api-contract/schemas/checkin.yaml, Phase 5 checklist D50): a {@code VERIFIED} scan
 * carries a string of 1 to 64 characters, a {@code REJECTED_SIGNATURE} one carries anything and the
 * server ignores it. Bean Validation cannot emit that, and the property is a {@code JsonNode} in
 * Java (any JSON value must bind), so this also stops it from being described as an object. The
 * rule itself is enforced by the web mapper; contract-diff then proves this equals the contract's.
 */
@Configuration
class CheckInOpenApiConfig {

    @Bean
    OpenApiCustomizer ticketCodeRule() {
        return openApi -> {
            var scanEvent = openApi.getComponents().getSchemas().get("ScanEvent");
            if (scanEvent == null) {
                return;
            }
            scanEvent.addProperty("ticketCode", new JsonSchema());

            var whenVerified = new JsonSchema();
            whenVerified.addProperty("deviceVerdict", constant("VERIFIED"));

            var code = new JsonSchema();
            code.setTypes(Set.of("string"));
            code.setMinLength(1);
            code.setMaxLength(64);
            var thenSchema = new JsonSchema();
            thenSchema.setRequired(List.of("ticketCode"));
            thenSchema.addProperty("ticketCode", code);

            scanEvent.setIf(whenVerified);
            scanEvent.setThen(thenSchema);
        };
    }

    private static Schema<?> constant(Object value) {
        var schema = new JsonSchema();
        schema.setConst(value);
        return schema;
    }
}
