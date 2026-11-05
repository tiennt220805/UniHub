package com.unihub.shared.web;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Declares {@code 429} with {@code Retry-After} on every operation except those marked {@link
 * RateLimitExempt} (distributed-coordination.md §3, Phase 7 checklist D90): the limiter sits in the
 * security chain in front of every controller, so any of them can answer it. One place, because a
 * per-method annotation is a place to forget; {@code contract-diff} then compares this against the
 * {@code TooManyRequests} response of {@code packages/api-contract/common/responses.yaml}. It
 * describes the response; the behaviour is {@code RateLimitGate}.
 */
@Component
class RateLimitedResponses implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RateLimitExempt.class)) {
            return operation;
        }
        // a reference, like the @ApiProblems responses: the component is declared once, named
        // `error` (ProblemSchema)
        Schema<?> problem = new Schema<>().$ref("#/components/schemas/error");
        operation
                .getResponses()
                .addApiResponse(
                        "429",
                        new ApiResponse()
                                .description(
                                        "The request budget is spent (distributed-coordination.md §3)")
                                .addHeaderObject(
                                        "Retry-After", new Header().schema(new IntegerSchema()))
                                .content(
                                        new Content()
                                                .addMediaType(
                                                        "application/problem+json",
                                                        new MediaType().schema(problem))));
        return operation;
    }
}
