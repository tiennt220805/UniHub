package com.unihub.workshop.infrastructure.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes, in the generated OpenAPI spec, the {@code isPaid}/{@code price} rule the contract
 * states with JSON Schema {@code if/then/else} (packages/api-contract/schemas/workshop.yaml): a
 * paid workshop has a price greater than zero, a free one has none. Bean Validation has no way to
 * emit that, so it is added to the two schemas that carry the pair; the rule itself is enforced by
 * {@code CreateWorkshopRequest}, and by the {@code Workshop} aggregate for responses. This only
 * describes — contract-diff then proves the description equals the contract's.
 */
@Configuration
class WorkshopOpenApiConfig {

    private static final List<String> SCHEMAS_WITH_PRICE_RULE =
            List.of("Workshop", "WorkshopSummary", "CreateWorkshopRequest");

    @Bean
    OpenApiCustomizer paidWorkshopPriceRule() {
        return openApi -> {
            var schemas = openApi.getComponents().getSchemas();
            for (var name : SCHEMAS_WITH_PRICE_RULE) {
                var schema = schemas.get(name);
                if (schema != null) {
                    applyPriceRule(schema);
                }
            }
            // @Schema(defaultValue) would emit the string "false"; the contract's default is the
            // boolean false.
            var create = schemas.get("CreateWorkshopRequest");
            if (create != null && create.getProperties() != null) {
                if (create.getProperties().get("isPaid") instanceof Schema<?> isPaid) {
                    isPaid.setDefault(false);
                }
            }
        };
    }

    private static void applyPriceRule(Schema<?> schema) {
        var whenPaid = new JsonSchema();
        whenPaid.addProperty("isPaid", constant(true));

        var positivePrice = new JsonSchema();
        positivePrice.setTypes(Set.of("number"));
        positivePrice.setExclusiveMinimumValue(BigDecimal.ZERO);
        var thenSchema = new JsonSchema();
        thenSchema.setRequired(List.of("price"));
        thenSchema.addProperty("price", positivePrice);

        var noPrice = new JsonSchema();
        noPrice.setTypes(Set.of("null"));
        var elseSchema = new JsonSchema();
        elseSchema.addProperty("price", noPrice);

        schema.setIf(whenPaid);
        schema.setThen(thenSchema);
        schema.setElse(elseSchema);
    }

    private static Schema<?> constant(Object value) {
        var schema = new JsonSchema();
        schema.setConst(value);
        return schema;
    }
}
