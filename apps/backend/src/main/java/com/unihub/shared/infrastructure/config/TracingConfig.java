package com.unihub.shared.infrastructure.config;

import com.unihub.shared.infrastructure.tracing.ScrubbedSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Tracing wiring that is ours and not Boot's (observability.md §2). Every {@link SpanExporter} the
 * application has, however it got there, goes through {@link ScrubbedSpanExporter}.
 */
@Configuration
class TracingConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static BeanPostProcessor scrubSpanExporters() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof SpanExporter exporter
                                && !(bean instanceof ScrubbedSpanExporter)
                        ? new ScrubbedSpanExporter(exporter)
                        : bean;
            }
        };
    }
}
