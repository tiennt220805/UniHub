package com.unihub.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Builds the RFC 7807 response for a sealed-Result variant (backend-coding-standards.md §17,
 * §25.3): a business outcome that is a Result, not an exception, so {@link GlobalProblemHandler}
 * never sees it. {@code instance} is the request path; {@code type} is one of {@link ProblemTypes}
 * when the client must branch on the reason.
 */
public final class Problems {

    private Problems() {}

    public static ResponseEntity<ProblemDetail> of(
            HttpStatus status, URI type, String detail, HttpServletRequest request) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(type);
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    public static ResponseEntity<ProblemDetail> conflict(
            HttpServletRequest request, URI type, String detail) {
        return of(HttpStatus.CONFLICT, type, detail, request);
    }
}
