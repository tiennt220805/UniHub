package com.unihub.shared.web;

import com.unihub.shared.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place HTTP errors become {@code application/problem+json} (backend-coding-standards.md
 * §17, packages/api-contract/common/error.yaml). Extending {@link ResponseEntityExceptionHandler}
 * turns every Spring MVC failure — a constraint violation on a request parameter, a malformed or
 * mistyped value, a failed {@code @Valid} body — into a {@code ProblemDetail} with status 400,
 * {@code instance} set to the request path. A {@link DomainException} maps by its {@link
 * DomainException#kind()}, so this class never imports a module's exception. Anything else a
 * handler throws is a bug: it becomes a 500 {@code ProblemDetail} whose detail says nothing about
 * the cause, and the cause is logged — without this, Spring Boot's {@code /error} page would answer
 * with its own {@code {timestamp, status, error, path}} JSON instead of the contract's shape.
 *
 * <p>Not yet here: {@code Retry-After} for {@code UNAVAILABLE} (no {@code RetryableUnavailable}
 * exists yet) and the {@code correlationId} property (no correlation-ID filter exists yet — see
 * {@code package-info}).
 */
@RestControllerAdvice
class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalProblemHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
        var status =
                switch (ex.kind()) {
                    case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        var body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Every exception no more specific handler claims. Spring Security's own exceptions are thrown
     * on: its filters turn them into the 401/403 problem bodies, and catching them here would turn
     * a {@code @PreAuthorize} refusal into a 500.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request)
            throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        var status = HttpStatus.INTERNAL_SERVER_ERROR;
        var body = ProblemDetail.forStatusAndDetail(status, UNEXPECTED_DETAIL);
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).body(body);
    }
}
