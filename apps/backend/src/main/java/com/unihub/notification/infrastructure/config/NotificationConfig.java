package com.unihub.notification.infrastructure.config;

import com.unihub.notification.application.usecase.NotificationDispatcher;
import com.unihub.notification.domain.model.NotificationPolicy;
import java.util.Properties;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Wires the Notification module's configuration into plain values and the two infrastructure beans
 * that need it: the SMTP sender and the listener container of {@code unihub.notification.queue}.
 */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfig {

    static final String LISTENER_CONTAINER_FACTORY = "notificationListenerContainerFactory";

    /**
     * Every configured channel must have a bean: a CHECK value or a configured type naming a
     * channel nobody implements fails the start instead of failing a delivery (notification.md §2).
     */
    @Bean
    NotificationPolicy notificationPolicy(
            NotificationProperties properties, NotificationDispatcher dispatcher) {
        var policy = new NotificationPolicy(properties.claimLease(), properties.channelsByType());
        var known = dispatcher.channelCodes();
        for (var entry : policy.channelsByType().entrySet()) {
            for (var code : entry.getValue()) {
                if (!known.contains(code)) {
                    throw new IllegalStateException(
                            "unihub.notification.channels configures "
                                    + code
                                    + " for "
                                    + entry.getKey()
                                    + ", but no NotificationChannel bean handles it (known: "
                                    + known
                                    + ")");
                }
            }
        }
        return policy;
    }

    @Bean
    JavaMailSenderImpl notificationMailSender(NotificationProperties properties) {
        var smtp = properties.smtp();
        var sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");
        var mail = new Properties();
        // All three: each one's default is to wait for ever, which would hold a listener thread.
        mail.put("mail.smtp.connectiontimeout", String.valueOf(smtp.connectTimeout().toMillis()));
        mail.put("mail.smtp.timeout", String.valueOf(smtp.readTimeout().toMillis()));
        mail.put("mail.smtp.writetimeout", String.valueOf(smtp.writeTimeout().toMillis()));
        if (smtp.username() != null && !smtp.username().isBlank()) {
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
            mail.put("mail.smtp.auth", "true");
        }
        if (smtp.starttls()) {
            mail.put("mail.smtp.starttls.enable", "true");
        }
        sender.setJavaMailProperties(mail);
        return sender;
    }

    /**
     * A factory of its own so this queue can have {@code concurrency} 2 and {@code prefetch} 1
     * (D39). Boot attaches the shared {@link ContainerCustomizer} (correlation id, and the failure
     * handler that republishes to the retry chain) only to <em>its own</em> default factory, not
     * through the configurer, so it is set here by hand: without it a failed message would be
     * dropped, since the business queues have no dead-letter exchange.
     */
    @Bean(LISTENER_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory notificationListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizer,
            NotificationProperties properties) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        customizer.ifUnique(factory::setContainerCustomizer);
        factory.setConcurrentConsumers(properties.listener().concurrency());
        factory.setMaxConcurrentConsumers(properties.listener().concurrency());
        factory.setPrefetchCount(properties.listener().prefetch());
        return factory;
    }
}
