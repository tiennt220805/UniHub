package com.unihub.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The two rate-limit filters (distributed-coordination.md §3). Both are added to the security chain
 * right after the bearer-token filter — they need to know whether the token was valid, and they run
 * before authorization — and are <b>not</b> beans: a servlet {@code Filter} bean would also be
 * registered in the container, outside the chain, where the authentication is not known. Extending
 * {@link OncePerRequestFilter} skips the container's error dispatch, so a 404 body is not charged a
 * second time.
 */
final class RateLimitFilters {

    private RateLimitFilters() {}

    /**
     * The general budget: the user when the token verified, the address otherwise. A request whose
     * token the bearer filter refused never gets here; the authentication entry point charges the
     * address for it.
     */
    static final class General extends OncePerRequestFilter {

        private final RateLimitGate gate;

        General(RateLimitGate gate) {
            this.gate = gate;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var admitted =
                    SecurityContextHolder.getContext().getAuthentication()
                                    instanceof JwtAuthenticationToken jwt
                            ? gate.admitUser(request, response, jwt.getName())
                            : gate.admitAddress(request, response);
            if (admitted) {
                chain.doFilter(request, response);
            }
        }
    }

    /** The registration budget, for a verified user only: an anonymous attempt is answered 401. */
    static final class Registration extends OncePerRequestFilter {

        private final RateLimitGate gate;

        Registration(RateLimitGate gate) {
            this.gate = gate;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication()
                    instanceof JwtAuthenticationToken jwt) {
                if (!gate.admitRegistration(request, response, jwt.getName())) {
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
