package com.parvez.android.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Committed outbox receipts survive SMTP failure. Delivery is at least once. */
@Component
public class PublicRequestEmailDelivery {
    private static final Logger log = LoggerFactory.getLogger(PublicRequestEmailDelivery.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    public PublicRequestEmailDelivery(JdbcTemplate jdbc, TransactionTemplate transaction,
            ObjectProvider<JavaMailSender> mail, @Value("${app.password-reset.from:}") String from) {
        this.jdbc = jdbc; this.transaction = transaction; this.mail = mail; this.from = from;
    }
    @Scheduled(fixedDelayString = "${books.requests.email-poll-millis:30000}", initialDelayString = "${books.requests.email-poll-millis:30000}")
    public void processPending() {
        var sender = mail.getIfAvailable();
        if (sender == null || from.isBlank()) return;
        for (int i = 0; i < 100; i++) {
            Boolean processed = transaction.execute(status -> {
                var rows = jdbc.queryForList("SELECT * FROM public_request_emails ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (rows.isEmpty()) return false;
                var row = rows.getFirst();
                var message = new SimpleMailMessage();
                message.setFrom(from); message.setTo((String) row.get("recipient"));
                message.setSubject("Your Booker public library request");
                message.setText((String) row.get("message"));
                try { sender.send(message); }
                catch (RuntimeException ex) {
                    log.warn("Public book request email delivery failed; it will be retried");
                    return false;
                }
                jdbc.update("DELETE FROM public_request_emails WHERE request_id=?", row.get("request_id"));
                return true;
            });
            if (!Boolean.TRUE.equals(processed)) return;
        }
    }
}
