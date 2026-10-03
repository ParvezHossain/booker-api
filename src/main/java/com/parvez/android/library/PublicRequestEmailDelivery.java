package com.parvez.android.library;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/** One broker delivery at a time; PostgreSQL retains retry and failed receipts. */
@Component
@EnableConfigurationProperties(RequestEmailSettings.class)
public class PublicRequestEmailDelivery {
    private static final Logger log = LoggerFactory.getLogger(PublicRequestEmailDelivery.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final RequestEmailSettings settings;

    public PublicRequestEmailDelivery(JdbcTemplate jdbc, TransactionTemplate transaction,
            ObjectProvider<JavaMailSender> mail, @Value("${app.password-reset.from:}") String from,
            RequestEmailSettings settings) {
        this.jdbc = jdbc; this.transaction = transaction; this.mail = mail;
        this.from = from; this.settings = settings;
    }

    public boolean isConfigured() { return !from.isBlank() && mail.getIfAvailable() != null; }

    public void deliver(UUID id) {
        transaction.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("""
                    SELECT * FROM public_request_emails
                    WHERE id=? AND failed_at IS NULL AND available_at <= now() FOR UPDATE
                    """, id);
            if (rows.isEmpty()) return; // Acknowledged duplicate, delayed retry or parked failure.
            var row = rows.getFirst();
            var sender = mail.getIfAvailable();
            if (sender == null || from.isBlank()) {
                retry(id, ((Number) row.get("attempts")).intValue(), false);
                return;
            }
            try {
                send(sender, row);
            } catch (RuntimeException | MessagingException failure) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                retry(id, attempts, attempts >= settings.maxAttempts());
                log.warn("Request email delivery failed; receipt {}", attempts >= settings.maxAttempts() ? "parked for operator review" : "scheduled for delayed retry");
                return;
            }
            jdbc.update("DELETE FROM public_request_emails WHERE id=?", id);
        });
    }

    private void retry(UUID id, int attempts, boolean failed) {
        long delay = Math.min(3600L, settings.retrySeconds() * (1L << Math.max(0, attempts - 1)));
        jdbc.update("""
                UPDATE public_request_emails SET attempts=?, published_at=NULL,
                    available_at=now() + (? * interval '1 second'),
                    failed_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?
                """, attempts, delay, failed, id);
    }

    private void send(JavaMailSender sender, Map<String, Object> row) throws MessagingException {
        if (row.get("html_message") instanceof String html) {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED, "UTF-8");
            helper.setFrom(from); helper.setTo((String) row.get("recipient"));
            helper.setSubject((String) row.get("subject"));
            helper.setText((String) row.get("message"), html);
            sender.send(message);
        } else {
            var message = new SimpleMailMessage();
            message.setFrom(from); message.setTo((String) row.get("recipient"));
            message.setSubject((String) row.get("subject"));
            message.setText((String) row.get("message"));
            sender.send(message);
        }
    }
}
