package com.unihub.shared.infrastructure.config;

import com.unihub.shared.web.CorrelationIdFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link CorrelationIdFilter} one step after Boot's observation filter (order {@code
 * HIGHEST_PRECEDENCE + 1}): by then the request's span is current. Spring Security's chain is far
 * later, so its 401 and 403 are inside this filter. Where tracing is switched off (a test that has
 * not asked for it) there is no {@code Tracer}, and the filter does nothing.
 */
@Configuration
class CorrelationIdConfig {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter(ObjectProvider<Tracer> tracer) {
        var registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter(tracer.getIfAvailable()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
