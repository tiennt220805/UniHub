package com.unihub.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 403: the caller is authenticated but lacks the permission the endpoint names. */
final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final String DETAIL = "You do not have permission to perform this action.";

    private final ObjectMapper json;

    ProblemDetailAccessDeniedHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        ProblemResponses.write(json, request, response, HttpStatus.FORBIDDEN, DETAIL);
    }
}
