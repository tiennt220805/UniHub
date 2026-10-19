package com.unihub.shared.infrastructure.config;

import com.unihub.shared.infrastructure.in.messaging.CorrelationIdListenerAdvice;
import com.unihub.shared.infrastructure.out.messaging.RetryChainRecoverer;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What every {@code @RabbitListener} container shares (backend-coding-standards.md §8.5–§8.6):
 *
 * <ol>
 *   <li>the correlation-id advice, outermost so everything below logs with the id;
 *   <li>a failure handler that runs after the <em>first</em> failed attempt ({@code
 *       maxAttempts(1)}: no in-process retry) and hands the message to {@link RetryChainRecoverer},
 *       which republishes it to the next retry stage or the parking lot.
 * </ol>
 *
 * A listener never catches an exception itself; it propagates to here.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
class ListenerContainerConfig {

    @Bean
    RetryChainRecoverer retryChainRecoverer(
            RabbitTemplate template, MessagingProperties properties) {
        return new RetryChainRecoverer(template, properties);
    }

    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> unihubListenerAdvice(
            RetryChainRecoverer recoverer) {
        return container -> {
            Advice retry =
                    RetryInterceptorBuilder.stateless().maxAttempts(1).recoverer(recoverer).build();
            container.setAdviceChain(new CorrelationIdListenerAdvice(), retry);
        };
    }
}
