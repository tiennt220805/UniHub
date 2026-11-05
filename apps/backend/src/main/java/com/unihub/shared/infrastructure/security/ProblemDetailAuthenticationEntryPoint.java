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
 * 6750) and a problem+json body. It is also where the address budget is charged for a request the
 * bearer filter refused before the general rate-limit filter ran (RateLimitGate). The challenge
 * names the error code only; the decoder's own message (which can describe why a signature or claim
 * was rejected) is never echoed.
 */
final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String DETAIL = "Authentication is required: send a valid bearer token.";

    private final ObjectMapper json;
    private final RateLimitGate rateLimit;

    /** {@code rateLimit} is null where the limiter is switched off. */
    ProblemDetailAuthenticationEntryPoint(ObjectMapper json, RateLimitGate rateLimit) {
        this.json = json;
        this.rateLimit = rateLimit;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        // A request that ends unauthenticated is charged to its address here, so a missing, expired
        // or forged token never skips the budget; over budget it is answered 429, not 401. A
        // request
        // the general filter already charged (no token at all) is not charged twice.
        if (rateLimit != null && !rateLimit.admitAddress(request, response)) {
            return;
        }
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
