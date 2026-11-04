package com.unihub.shared.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.out.messaging.AmqpAuditPublisher;
import com.unihub.shared.infrastructure.out.messaging.AuditPublisher;
import com.unihub.shared.infrastructure.out.messaging.RabbitAuditTrail;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The audit publisher (backend-coding-standards.md §12, Phase 6 checklist D68). Three beans, none
 * of them a {@code ConnectionFactory} or a {@code RabbitTemplate}: Boot's auto-configuration backs
 * off from either of those, so the audit connection stays inside {@link AmqpAuditPublisher}.
 */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
class AuditConfig {

    @Bean
    AuditPublisher auditPublisher(
            RabbitProperties rabbit, AuditProperties properties, ApplicationContext context) {
        return new AmqpAuditPublisher(rabbit, properties, context);
    }

    /**
     * The executor is built here and owned by the trail, not exposed as a bean: Boot's own task
     * executor is conditional on there being no {@code Executor} bean.
     *
     * <p>One thread, a fixed number of waiting entries, and a full queue refuses instead of waiting
     * (the trail counts it as dropped). A daemon thread: a publish stuck on a blocked socket must
     * not keep the JVM from exiting.
     */
    private static ExecutorService executorOf(AuditProperties properties) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.executorQueueCapacity()),
                task -> {
                    var thread = new Thread(task, "audit-publisher");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    RabbitAuditTrail rabbitAuditTrail(
            AuditPublisher publisher,
            ObjectMapper json,
            MeterRegistry meters,
            AuditProperties properties,
            ObjectProvider<CurrentTraceContext> traces) {
        return new RabbitAuditTrail(
                publisher,
                json,
                meters,
                executorOf(properties),
                properties.drainTimeout(),
                traces.getIfAvailable());
    }
}
