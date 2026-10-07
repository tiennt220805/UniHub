package com.unihub.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401: no token, or a token that failed verification. Answers with a {@code Bearer} challenge (RFC
 * 6750) and a problem+json body. The challenge names the error code only; the decoder's own message
 * (which can describe why a signature or claim was rejected) is never echoed.
 */
final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String DETAIL = "Authentication is required: send a valid bearer token.";

    private final ObjectMapper json;

    ProblemDetailAuthenticationEntryPoint(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(exception));
        ProblemResponses.write(json, request, response, HttpStatus.UNAUTHORIZED, DETAIL);
    }

    private static String challenge(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            return "Bearer error=\"" + oauth2.getError().getErrorCode() + "\"";
        }
        return "Bearer";
    }
}
