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
@ConditionalOnProperty(name = "app.email-activation.email.enabled", havingValue = "true", matchIfMissing = true)
public class EmailActivationEmailQueueConfiguration {
    @Bean Queue emailActivationEmailQueue(EmailActivationEmailSettings settings,
            @Value("${books.requests.email.queue:booker.request-emails}") String requestQueue,
            @Value("${app.password-reset.email.queue:booker.password-reset-emails}") String resetQueue,
            @Value("${app.password-change.email.queue:booker.password-change-emails}") String changeQueue) {
        for (String other : java.util.List.of(requestQueue, resetQueue, changeQueue)) {
            if (settings.queue().equals(other) || settings.queue().equals(other + ".dead")
                    || (settings.queue() + ".dead").equals(other))
                throw new IllegalArgumentException("Email activation queue and dead queue must be distinct from other email queues");
        }
        return QueueBuilder.durable(settings.queue()).quorum().singleActiveConsumer()
                .maxLength(settings.queueLimit()).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange("").deadLetterRoutingKey(settings.queue() + ".dead")
                .withArgument("x-dead-letter-strategy", "at-least-once").build();
    }
    @Bean Queue emailActivationEmailDeadQueue(EmailActivationEmailSettings settings) {
        return QueueBuilder.durable(settings.queue() + ".dead").quorum()
                .maxLength(settings.queueLimit()).overflow(QueueBuilder.Overflow.rejectPublish).build();
    }
    @Bean SimpleRabbitListenerContainerFactory emailActivationEmailListenerFactory(ConnectionFactory connectionFactory,
            @Value("${app.email-activation.email.listener-auto-startup:true}") boolean autoStartup) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(1); factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
    @Bean ThreadPoolTaskScheduler emailActivationEmailScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("email-activation-email-publisher-");
        return scheduler;
    }
}
