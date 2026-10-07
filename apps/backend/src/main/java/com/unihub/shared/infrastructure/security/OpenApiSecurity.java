package com.unihub.shared.infrastructure.security;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Describes, in the generated OpenAPI spec, the same authentication the contract declares
 * (packages/api-contract/common/security-schemes.yaml): an HTTP bearer JWT required by default.
 * Public operations opt out with an empty {@code @SecurityRequirements}. This only describes; the
 * enforcement is {@link SecurityConfig}.
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = OpenApiSecurity.BEARER_AUTH))
@SecurityScheme(
        name = OpenApiSecurity.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
class OpenApiSecurity {

    static final String BEARER_AUTH = "bearerAuth";
}
