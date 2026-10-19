package com.unihub.shared.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.out.messaging.CorrelationIdPostProcessor;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker-side wiring shared by every module: the topology to declare, the JSON converter, and the
 * one place the correlation id is copied onto outgoing messages (observability.md §1). The
 * connection itself is opened lazily by Spring AMQP, so declaring all this needs no broker at boot.
 */
@Configuration
class RabbitMessagingConfig {

    /**
     * Picked up by Boot's {@code RabbitAdmin}, which declares it when the first connection opens.
     */
    @Bean
    Declarables unihubTopology() {
        return Topology.declarables();
    }

    /**
     * The target type of a message is inferred from the listener's parameter, never from a class
     * name sent by the producer, so a producer's class name is not part of the wire contract
     * (backend-coding-standards.md §8.5).
     */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        var converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    /** Registered once on the shared template, not at each publish call site. */
    @Bean
    RabbitTemplateCustomizer correlationIdOnEveryPublish() {
        return template ->
                template.addBeforePublishPostProcessors(new CorrelationIdPostProcessor());
    }
}
