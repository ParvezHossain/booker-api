package com.parvez.android.auth;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.password-reset.email.enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetEmailQueueConfiguration {
    @Bean Queue passwordResetEmailQueue(PasswordResetEmailSettings settings,
            @Value("${books.requests.email.queue:booker.request-emails}") String requestQueue) {
        if (settings.queue().equals(requestQueue) || settings.queue().equals(requestQueue + ".dead")
                || (settings.queue() + ".dead").equals(requestQueue)) {
            throw new IllegalArgumentException("Password reset and book-request email queues must have distinct names");
        }
        return QueueBuilder.durable(settings.queue()).quorum().singleActiveConsumer()
                .maxLength(settings.queueLimit()).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange("").deadLetterRoutingKey(settings.queue() + ".dead")
                .withArgument("x-dead-letter-strategy", "at-least-once").build();
    }
    @Bean Queue passwordResetEmailDeadQueue(PasswordResetEmailSettings settings) {
        return QueueBuilder.durable(settings.queue() + ".dead").quorum()
                .maxLength(settings.queueLimit()).overflow(QueueBuilder.Overflow.rejectPublish).build();
    }
    @Bean SimpleRabbitListenerContainerFactory passwordResetEmailListenerFactory(ConnectionFactory connectionFactory,
            @Value("${app.password-reset.email.listener-auto-startup:true}") boolean autoStartup) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(1); factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
    @Bean ThreadPoolTaskScheduler passwordResetEmailScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("password-reset-email-publisher-");
        return scheduler;
    }
}
