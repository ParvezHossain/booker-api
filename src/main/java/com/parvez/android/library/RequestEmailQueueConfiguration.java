package com.parvez.android.library;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "books.requests.email.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RequestEmailSettings.class)
public class RequestEmailQueueConfiguration {
    @Bean Queue requestEmailQueue(RequestEmailSettings settings) {
        return QueueBuilder.durable(settings.queue()).quorum().singleActiveConsumer()
                .maxLength(settings.queueLimit()).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange("").deadLetterRoutingKey(settings.queue() + ".dead")
                .withArgument("x-dead-letter-strategy", "at-least-once").build();
    }
    @Bean Queue requestEmailDeadQueue(RequestEmailSettings settings) {
        return QueueBuilder.durable(settings.queue() + ".dead").quorum()
                .maxLength(settings.queueLimit()).overflow(QueueBuilder.Overflow.rejectPublish).build();
    }
    @Bean SimpleRabbitListenerContainerFactory requestEmailListenerFactory(ConnectionFactory connectionFactory,
            @Value("${books.requests.email.listener-auto-startup:true}") boolean autoStartup) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        // Infrastructure/invalid-message failures are quarantined, never hot-looped.
        // Pending database receipts are independently eligible for later redispatch.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
    @Bean ThreadPoolTaskScheduler requestEmailScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("request-email-publisher-");
        return scheduler;
    }
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        // Preserve a separate default scheduler for Drive/storage workers.
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("application-worker-");
        return scheduler;
    }
}
