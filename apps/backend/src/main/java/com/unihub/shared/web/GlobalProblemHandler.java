package com.unihub.shared.web;

import com.unihub.shared.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place HTTP errors become {@code application/problem+json} (backend-coding-standards.md
 * §17, packages/api-contract/common/error.yaml). Extending {@link ResponseEntityExceptionHandler}
 * turns every Spring MVC failure — a constraint violation on a request parameter, a malformed or
 * mistyped value, a failed {@code @Valid} body — into a {@code ProblemDetail} with status 400,
 * {@code instance} set to the request path. A {@link DomainException} maps by its {@link
 * DomainException#kind()}, so this class never imports a module's exception.
 *
 * <p>Not yet here: {@code Retry-After} for {@code UNAVAILABLE} (no {@code RetryableUnavailable}
 * exists yet) and the {@code correlationId} property (no correlation-ID filter exists yet — see
 * {@code package-info}).
 */
@RestControllerAdvice
class GlobalProblemHandler extends ResponseEntityExceptionHandler {

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
}
