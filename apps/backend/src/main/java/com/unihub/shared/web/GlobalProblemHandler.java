package com.unihub.shared.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place HTTP errors become {@code application/problem+json} (backend-coding-standards.md
 * §17, packages/api-contract/common/error.yaml). Extending {@link ResponseEntityExceptionHandler}
 * turns every Spring MVC failure — a constraint violation on a request parameter, a malformed or
 * mistyped value — into a {@code ProblemDetail} with status 400, {@code instance} set to the
 * request path.
 *
 * <p>Not yet here: the {@code DomainException} → 404/409/503 mapping (no domain exception exists
 * yet) and the {@code correlationId} property (no correlation-ID filter exists yet — see {@code
 * package-info}).
 */
@RestControllerAdvice
class GlobalProblemHandler extends ResponseEntityExceptionHandler {}
