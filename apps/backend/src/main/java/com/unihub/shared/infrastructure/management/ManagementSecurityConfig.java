package com.unihub.shared.infrastructure.management;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security chain of the management port (observability.md §5, Phase 6 checklist D64). The API's
 * own chain denies everything but a short list of public paths, and Boot applies it to the
 * management port too, so without this chain Prometheus would meet a {@code 401}. {@code health}
 * and {@code prometheus} are open here because the port is never routed from the Ingress (Phase 7)
 * and is reachable only from inside the cluster or the developer's machine. Any other actuator
 * endpoint is denied even if someone exposes it by configuration: opening one more takes a code
 * change in this file, not a property.
 */
@Configuration
class ManagementSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(EndpointRequest.to("health", "prometheus"))
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll());
        return http.build();
    }
}
