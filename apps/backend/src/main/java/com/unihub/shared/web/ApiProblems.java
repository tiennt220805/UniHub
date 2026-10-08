package com.unihub.shared.web;

import com.unihub.shared.web.dto.ProblemSchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documentation-only shorthands for the error responses the contract lists on an operation
 * (packages/api-contract/common/error.yaml): each is just {@code @ApiResponse} with the RFC 7807
 * body, so a controller method states which statuses it can return without repeating the body every
 * time. They describe the response for contract-diff; the behaviour is {@link GlobalProblemHandler}
 * and the Spring Security handlers.
 */
public final class ApiProblems {

    private static final String PROBLEM_JSON = "application/problem+json";

    private ApiProblems() {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "400",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemSchema.class)))
    public @interface BadRequest {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "401",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemSchema.class)))
    public @interface Unauthorized {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "403",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemSchema.class)))
    public @interface Forbidden {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "404",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemSchema.class)))
    public @interface NotFound {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "409",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemSchema.class)))
    public @interface Conflict {}
}
