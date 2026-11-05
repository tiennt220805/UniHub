package com.unihub.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.command.ConsumeRateLimitBudgetCommand;
import com.unihub.shared.application.port.in.ConsumeRateLimitBudgetUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Where a request is charged to a budget and, over budget, answered {@code 429} (distributed-
 * coordination.md §3; Phase 7 checklist D90). One place, used by the two filters and by the
 * authentication entry point, so a request is charged once and never skips the charge:
 *
 * <ul>
 *   <li>a request with a valid token is charged to its user ({@code global-user:{userId}});
 *   <li>a request without one is charged to its address ({@code global-ip:{address}}), by the
 *       general filter when it reaches it, or by the entry point when the bearer filter refused the
 *       token first (a forged token never skips the budget);
 *   <li>{@code POST /api/v1/registrations} is also charged to the user ({@code
 *       registration:{userId}});
 *   <li>the payment webhook is exempt everywhere: its body is signature-verified and the gateway
 *       retries from few addresses.
 * </ul>
 *
 * The first charge of a request is remembered in a request attribute, so the entry point does not
 * charge a request the general filter already charged.
 */
final class RateLimitGate {

    static final String KEY_PREFIX = "unihub:ratelimit:";

    private static final String CHARGED = RateLimitGate.class.getName() + ".charged";
    private static final String WEBHOOK_PATH = "/api/v1/webhooks/payment-gateway";
    private static final String REGISTRATIONS_PATH = "/api/v1/registrations";

    private final ConsumeRateLimitBudgetUseCase budgets;
    private final RateLimitProperties properties;
    private final ObjectMapper json;

    RateLimitGate(
            ConsumeRateLimitBudgetUseCase budgets,
            RateLimitProperties properties,
            ObjectMapper json) {
        this.budgets = budgets;
        this.properties = properties;
        this.json = json;
    }

    /** The general budget of an authenticated user. {@code false}: a 429 was written. */
    boolean admitUser(HttpServletRequest request, HttpServletResponse response, String userId)
            throws IOException {
        return admitGeneral(request, response, "global-user:" + userId);
    }

    /** The general budget of an address, for a request with no valid token. */
    boolean admitAddress(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return admitGeneral(request, response, "global-ip:" + ClientAddress.of(request));
    }

    /** The registration budget of an authenticated user; any other request passes. */
    boolean admitRegistration(
            HttpServletRequest request, HttpServletResponse response, String userId)
            throws IOException {
        if (!isRegistrationAttempt(request)) {
            return true;
        }
        return consume(
                request,
                response,
                "registration:" + userId,
                properties.registrationLimit(),
                properties.registrationWindow());
    }

    private boolean admitGeneral(
            HttpServletRequest request, HttpServletResponse response, String identity)
            throws IOException {
        if (exempt(request) || charged(request)) {
            return true;
        }
        request.setAttribute(CHARGED, Boolean.TRUE);
        return consume(
                request, response, identity, properties.generalLimit(), properties.generalWindow());
    }

    private boolean consume(
            HttpServletRequest request,
            HttpServletResponse response,
            String identity,
            int limit,
            Duration window)
            throws IOException {
        var outcome =
                budgets.consume(
                        new ConsumeRateLimitBudgetCommand(KEY_PREFIX + identity, limit, window));
        if (outcome.allowed()) {
            return true;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(outcome.retryAfterSeconds()));
        ProblemResponses.write(
                json,
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests: wait " + outcome.retryAfterSeconds() + " seconds and retry.");
        return false;
    }

    static boolean exempt(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && WEBHOOK_PATH.equals(request.getRequestURI());
    }

    private static boolean isRegistrationAttempt(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && REGISTRATIONS_PATH.equals(request.getRequestURI());
    }

    private static boolean charged(HttpServletRequest request) {
        return request.getAttribute(CHARGED) != null;
    }
}
