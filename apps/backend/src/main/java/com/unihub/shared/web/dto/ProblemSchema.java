package com.unihub.shared.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only: describes the {@code error} component of
 * packages/api-contract/common/error.yaml so the generated OpenAPI spec names and shapes it the
 * same way. Never instantiated — the runtime body is Spring's {@code ProblemDetail}
 * (backend-coding-standards.md §17).
 */
@Schema(
        name = "error",
        types = {"object"},
        requiredProperties = {"type", "title", "status"})
public record ProblemSchema(
        @Schema(format = "uri-reference") String type,
        String title,
        int status,
        String detail,
        @Schema(format = "uri-reference") String instance,
        @Schema(pattern = "^[0-9a-f]{32}$") String correlationId) {}
