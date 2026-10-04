package com.parvez.android.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "books.requests.email.enabled", havingValue = "true", matchIfMissing = true)
public class RequestEmailPublisher {
    private static final Logger log = LoggerFactory.getLogger(RequestEmailPublisher.class);
    private boolean configurationWarningLogged;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RabbitTemplate rabbit;
    private final RequestEmailSettings settings;
    private final PublicRequestEmailDelivery delivery;

    public RequestEmailPublisher(JdbcTemplate jdbc, TransactionTemplate transaction, RabbitTemplate rabbit,
                                 RequestEmailSettings settings, PublicRequestEmailDelivery delivery) {
        this.jdbc = jdbc; this.transaction = transaction; this.rabbit = rabbit;
        this.settings = settings; this.delivery = delivery;
    }

    @Scheduled(scheduler = "requestEmailScheduler", fixedDelayString = "${books.requests.email-poll-millis:1000}",
            initialDelayString = "${books.requests.email-poll-millis:1000}")
    public void processPending() {
        if (!delivery.isConfigured()) {
            if (!configurationWarningLogged) {
                log.warn("Request email delivery paused: configure SMTP_HOST and BOOK_REQUEST_EMAIL_FROM "
                        + "(or PASSWORD_RESET_FROM / SMTP_USERNAME); database receipts remain pending");
                configurationWarningLogged = true;
            }
            return;
        }
        configurationWarningLogged = false;
        try {
            for (int i = 0; i < settings.batchSize(); i++) {
                Boolean published = transaction.execute(status -> publishOne());
                if (!Boolean.TRUE.equals(published)) return;
            }
        } catch (RuntimeException failure) {
            log.warn("Request email queue publication unavailable; database receipts remain pending");
        }
    }

    private boolean publishOne() {
        var rows = jdbc.queryForList("""
                SELECT id FROM public_request_emails
                WHERE failed_at IS NULL AND available_at <= now()
                  AND (published_at IS NULL OR published_at <= now() - (? * interval '1 second'))
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, settings.redispatchSeconds());
        if (rows.isEmpty()) return false;
        UUID id = (UUID) rows.getFirst().get("id");
        var properties = new MessageProperties();
        properties.setContentType("text/plain");
        properties.setContentEncoding("US-ASCII");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(id.toString());
        var correlation = new CorrelationData();
        rabbit.send("", settings.queue(), new Message(id.toString().getBytes(StandardCharsets.US_ASCII), properties), correlation);
        try {
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null)
                throw new IllegalStateException("Request email publication was not confirmed/routed");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request email publication interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Request email publication not confirmed", failure);
        }
        jdbc.update("UPDATE public_request_emails SET published_at=now() WHERE id=?", id);
        return true;
    }
}
