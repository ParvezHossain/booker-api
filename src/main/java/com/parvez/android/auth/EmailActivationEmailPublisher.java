package com.parvez.android.auth;

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

/** Bounded confirmed publication of opaque IDs; PostgreSQL remains authoritative. */
@Component
@ConditionalOnProperty(name = "app.email-activation.email.enabled", havingValue = "true", matchIfMissing = true)
public class EmailActivationEmailPublisher {
    private static final Logger log = LoggerFactory.getLogger(EmailActivationEmailPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RabbitTemplate rabbit;
    private final EmailActivationEmailSettings settings;
    private final SmtpEmailActivationDelivery delivery;
    private boolean configurationWarningLogged;

    public EmailActivationEmailPublisher(JdbcTemplate jdbc, TransactionTemplate transaction, RabbitTemplate rabbit,
            EmailActivationEmailSettings settings, SmtpEmailActivationDelivery delivery) {
        this.jdbc = jdbc; this.transaction = transaction; this.rabbit = rabbit; this.settings = settings; this.delivery = delivery;
    }

    @Scheduled(scheduler = "emailActivationEmailScheduler", fixedDelayString = "${app.email-activation.email.poll-millis:1000}",
            initialDelayString = "${app.email-activation.email.poll-millis:1000}")
    public void processPending() {
        try {
            // Bounded cleanup also removes parked ciphertext after expiry/replacement.
            transaction.executeWithoutResult(status -> jdbc.update("""
                    DELETE FROM email_activation_emails WHERE id IN (
                        SELECT e.id FROM email_activation_emails e WHERE
                            (e.expires_at <= clock_timestamp() OR NOT EXISTS (
                                SELECT 1 FROM email_activation_tokens t JOIN workspace_users u ON u.email=t.email WHERE t.email=e.email
                                    AND t.token_hash=e.token_hash AND t.expires_at > clock_timestamp() AND NOT u.email_verified))
                            AND (e.lease_until IS NULL OR e.lease_until <= clock_timestamp())
                        ORDER BY e.created_at LIMIT ? FOR UPDATE OF e SKIP LOCKED)
                    """, settings.batchSize()));
            if (!delivery.isConfigured()) {
                if (!configurationWarningLogged) {
                    log.warn("Email activation email publication paused: configure SMTP, EMAIL_ACTIVATION_FROM and EMAIL_ACTIVATION_EMAIL_ENCRYPTION_KEY");
                    configurationWarningLogged = true;
                }
                return;
            }
            configurationWarningLogged = false;
            for (int i = 0; i < settings.batchSize(); i++) {
                if (!Boolean.TRUE.equals(transaction.execute(status -> publishOne()))) return;
            }
        } catch (RuntimeException failure) {
            log.warn("Email activation email publication unavailable; database receipts remain pending");
        }
    }

    private boolean publishOne() {
        var rows = jdbc.queryForList("""
                SELECT id FROM email_activation_emails
                WHERE failed_at IS NULL AND expires_at > clock_timestamp() AND available_at <= clock_timestamp()
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    AND (published_at IS NULL OR published_at <= clock_timestamp() - (? * interval '1 second'))
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, settings.redispatchSeconds());
        if (rows.isEmpty()) return false;
        UUID id = (UUID) rows.getFirst().get("id");
        var properties = new MessageProperties();
        properties.setContentType("text/plain"); properties.setContentEncoding("US-ASCII");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT); properties.setMessageId(id.toString());
        var correlation = new CorrelationData();
        rabbit.send("", settings.queue(), new Message(id.toString().getBytes(StandardCharsets.US_ASCII), properties), correlation);
        try {
            var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirmation.ack() || correlation.getReturned() != null)
                throw new IllegalStateException("Email activation email publication was not confirmed/routed");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Email activation email publication interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Email activation email publication not confirmed");
        }
        jdbc.update("UPDATE email_activation_emails SET published_at=clock_timestamp() WHERE id=?", id);
        return true;
    }
}
