package com.unihub.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.command.ConsumeRateLimitBudgetCommand;
import com.unihub.shared.application.port.in.ConsumeRateLimitBudgetUseCase;
import com.unihub.shared.application.result.RateLimitOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Where a request is charged and what it is charged to (distributed-coordination.md §3, Phase 7
 * D90): keys, limits, the one-charge rule, the webhook's exemption and the {@code 429} written.
 */
class RateLimitGateTest {

    /** An in-memory budget per key: allows {@code limit} calls, then rejects. */
    private static final class Budgets implements ConsumeRateLimitBudgetUseCase {
        final List<ConsumeRateLimitBudgetCommand> calls = new ArrayList<>();
        private final Map<String, Integer> used = new HashMap<>();

        @Override
        public RateLimitOutcome consume(ConsumeRateLimitBudgetCommand command) {
            calls.add(command);
            int count = used.merge(command.key(), 1, Integer::sum);
            return count <= command.limit()
                    ? RateLimitOutcome.permit()
                    : RateLimitOutcome.reject(7);
        }
    }

    private final Budgets budgets = new Budgets();
    private final RateLimitGate gate =
            new RateLimitGate(
                    budgets,
                    new RateLimitProperties(
                            true,
                            100,
                            Duration.ofSeconds(60),
                            5,
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(5)),
                    new ObjectMapper());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String path, String peer) {
        var request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(peer);
        return request;
    }

    @Test
    @DisplayName("a user is charged to global-user:{userId}: 100 per 60 s")
    void user_isChargedByUserId() throws Exception {
        var admitted =
                gate.admitUser(
                        request("GET", "/api/v1/workshops", "10.0.0.1"),
                        new MockHttpServletResponse(),
                        "u-1");

        assertThat(admitted).isTrue();
        var call = budgets.calls.get(0);
        assertThat(call.key()).isEqualTo("unihub:ratelimit:global-user:u-1");
        assertThat(call.limit()).isEqualTo(100);
        assertThat(call.window()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("an anonymous request is charged to global-ip:{address}")
    void anonymous_isChargedByAddress() throws Exception {
        gate.admitAddress(
                request("GET", "/api/v1/workshops", "10.0.0.1"), new MockHttpServletResponse());

        assertThat(budgets.calls.get(0).key()).isEqualTo("unihub:ratelimit:global-ip:10.0.0.1");
    }

    @Test
    @DisplayName("the 101st request is answered 429 with Retry-After and a problem+json body")
    void overBudget_writes429() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(
                            gate.admitUser(
                                    request("GET", "/api/v1/workshops", "10.0.0.1"),
                                    new MockHttpServletResponse(),
                                    "u-1"))
                    .as("request %d", i + 1)
                    .isTrue();
        }
        var response = new MockHttpServletResponse();

        var admitted =
                gate.admitUser(request("GET", "/api/v1/workshops", "10.0.0.1"), response, "u-1");

        assertThat(admitted).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":429");
    }

    @Test
    @DisplayName("two users from one address do not share a budget")
    void twoUsers_doNotShare() throws Exception {
        for (int i = 0; i < 101; i++) {
            gate.admitUser(
                    request("GET", "/api/v1/workshops", "10.0.0.1"),
                    new MockHttpServletResponse(),
                    "u-1");
        }

        assertThat(
                        gate.admitUser(
                                request("GET", "/api/v1/workshops", "10.0.0.1"),
                                new MockHttpServletResponse(),
                                "u-2"))
                .isTrue();
    }

    @Test
    @DisplayName(
            "a request is charged once: the entry point does not charge what the filter charged")
    void charged_onlyOnce() throws Exception {
        var request = request("GET", "/api/v1/registrations", "10.0.0.1");

        gate.admitAddress(request, new MockHttpServletResponse()); // the general filter
        gate.admitAddress(request, new MockHttpServletResponse()); // then the entry point

        assertThat(budgets.calls).hasSize(1);
    }

    @Test
    @DisplayName("the payment webhook is never charged, whatever its Authorization header")
    void webhook_isExempt() throws Exception {
        var request = request("POST", "/api/v1/webhooks/payment-gateway", "10.0.0.1");
        request.addHeader("Authorization", "Bearer junk");

        assertThat(gate.admitAddress(request, new MockHttpServletResponse())).isTrue();
        assertThat(gate.admitUser(request, new MockHttpServletResponse(), "u-1")).isTrue();
        assertThat(budgets.calls).isEmpty();
    }

    @Test
    @DisplayName("only the webhook's own method and path are exempt")
    void webhook_exemptionIsExact() throws Exception {
        gate.admitAddress(
                request("GET", "/api/v1/webhooks/payment-gateway", "10.0.0.1"),
                new MockHttpServletResponse());
        gate.admitAddress(
                request("POST", "/api/v1/webhooks/payment-gateway/x", "10.0.0.1"),
                new MockHttpServletResponse());

        assertThat(budgets.calls).hasSize(2);
    }

    @Test
    @DisplayName("POST /api/v1/registrations is also charged to registration:{userId}: 5 per 10 s")
    void registration_hasItsOwnBudget() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(
                            gate.admitRegistration(
                                    request("POST", "/api/v1/registrations", "10.0.0.1"),
                                    new MockHttpServletResponse(),
                                    "u-1"))
                    .as("attempt %d", i + 1)
                    .isTrue();
        }
        var response = new MockHttpServletResponse();

        assertThat(
                        gate.admitRegistration(
                                request("POST", "/api/v1/registrations", "10.0.0.1"),
                                response,
                                "u-1"))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        var call = budgets.calls.get(0);
        assertThat(call.key()).isEqualTo("unihub:ratelimit:registration:u-1");
        assertThat(call.limit()).isEqualTo(5);
        assertThat(call.window()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the registration budget is for the registration POST only")
    void registration_budgetIsOnlyForThatOperation() throws Exception {
        assertThat(
                        gate.admitRegistration(
                                request("GET", "/api/v1/registrations", "10.0.0.1"),
                                new MockHttpServletResponse(),
                                "u-1"))
                .isTrue();
        assertThat(
                        gate.admitRegistration(
                                request("POST", "/api/v1/registrations/abc/cancel", "10.0.0.1"),
                                new MockHttpServletResponse(),
                                "u-1"))
                .isTrue();
        assertThat(budgets.calls).isEmpty();
    }

    @Test
    @DisplayName("the filters pick the user's budget for a verified token, the address otherwise")
    void filter_choosesByAuthentication() throws Exception {
        var jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("u-9").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        new RateLimitFilters.General(gate)
                .doFilter(
                        request("GET", "/api/v1/workshops", "10.0.0.1"),
                        new MockHttpServletResponse(),
                        (rq, rs) -> {});
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        new RateLimitFilters.General(gate)
                .doFilter(
                        request("GET", "/api/v1/workshops", "10.0.0.2"),
                        new MockHttpServletResponse(),
                        (rq, rs) -> {});

        assertThat(budgets.calls)
                .extracting(ConsumeRateLimitBudgetCommand::key)
                .containsExactly(
                        "unihub:ratelimit:global-user:u-9", "unihub:ratelimit:global-ip:10.0.0.2");
    }

    @Test
    @DisplayName("over budget the filter stops the chain")
    void filter_stopsTheChain() throws Exception {
        for (int i = 0; i < 101; i++) {
            gate.admitAddress(
                    request("GET", "/api/v1/workshops", "10.0.0.3"), new MockHttpServletResponse());
        }
        var reached = new boolean[1];

        new RateLimitFilters.General(gate)
                .doFilter(
                        request("GET", "/api/v1/workshops", "10.0.0.3"),
                        new MockHttpServletResponse(),
                        (rq, rs) -> reached[0] = true);

        assertThat(reached[0]).isFalse();
    }

    @Test
    @DisplayName(
            "the entry point charges the address of a refused token, and answers 429 over budget")
    void entryPoint_chargesTheAddress() throws Exception {
        var entryPoint = new ProblemDetailAuthenticationEntryPoint(new ObjectMapper(), gate);
        for (int i = 0; i < 100; i++) {
            var response = new MockHttpServletResponse();
            entryPoint.commence(
                    request("GET", "/api/v1/registrations", "10.0.0.4"),
                    response,
                    new org.springframework.security.oauth2.server.resource
                            .InvalidBearerTokenException("x"));
            assertThat(response.getStatus()).isEqualTo(401);
        }
        var over = new MockHttpServletResponse();

        entryPoint.commence(
                request("GET", "/api/v1/registrations", "10.0.0.4"),
                over,
                new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException(
                        "x"));

        assertThat(over.getStatus()).isEqualTo(429);
        assertThat(over.getHeader("WWW-Authenticate")).isNull();
    }
}
