package com.parvez.android.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Lease ownership protects completion/retry; SMTP never holds database locks. */
@Component
@EnableConfigurationProperties(PasswordChangeEmailSettings.class)
public class PasswordChangeEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(PasswordChangeEmailWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SmtpPasswordChangeDelivery smtp;
    private final PasswordChangeEmailSettings settings;

    public PasswordChangeEmailWorker(JdbcTemplate jdbc, TransactionTemplate transaction,
            SmtpPasswordChangeDelivery smtp, PasswordChangeEmailSettings settings) {
        this.jdbc = jdbc; this.transaction = transaction; this.smtp = smtp; this.settings = settings;
    }

    public void deliver(UUID id) {
        var receipt = transaction.execute(status -> claim(id));
        if (receipt == null) return;
        boolean sent = false;
        try {
            sent = smtp.send(receipt.email(), receipt.changedAt(), receipt.source(), receipt.ip(),
                    receipt.browser(), receipt.device(), receipt.userAgent());
        } catch (RuntimeException failure) {
            log.warn("Password change notification could not be prepared; scheduling bounded retry");
        }
        boolean delivered = sent;
        transaction.executeWithoutResult(status -> {
            if (delivered) {
                // Audit history remains after the completed receipt is removed.
                jdbc.update("DELETE FROM password_change_emails WHERE id=? AND lease_id=?", id, receipt.lease());
            } else {
                int attempts = receipt.attempts() + 1;
                long delay = Math.min(3600L, settings.retrySeconds() * (1L << Math.max(0, attempts - 1)));
                jdbc.update("""
                        UPDATE password_change_emails SET attempts=?, published_at=NULL,
                            available_at=clock_timestamp() + (? * interval '1 second'),
                            failed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                            lease_id=NULL, lease_until=NULL WHERE id=? AND lease_id=?
                        """, attempts, delay, attempts >= settings.maxAttempts(), id, receipt.lease());
                log.warn("Password change notification {}",
                        attempts >= settings.maxAttempts() ? "parked for operator review" : "scheduled for delayed retry");
            }
        });
    }

    private Receipt claim(UUID id) {
        var rows = jdbc.queryForList("""
                SELECT h.*, e.attempts FROM password_change_emails e JOIN password_change_history h ON h.id=e.id
                WHERE e.id=? AND e.failed_at IS NULL AND e.available_at <= clock_timestamp()
                    AND (e.lease_until IS NULL OR e.lease_until <= clock_timestamp()) FOR UPDATE OF e
                """, id);
        if (rows.isEmpty()) return null;
        if (!smtp.isConfigured()) {
            jdbc.update("UPDATE password_change_emails SET published_at=NULL, available_at=clock_timestamp() + (? * interval '1 second') WHERE id=?",
                    settings.retrySeconds(), id);
            return null;
        }
        var row = rows.getFirst();
        UUID lease = UUID.randomUUID();
        jdbc.update("UPDATE password_change_emails SET lease_id=?, lease_until=clock_timestamp() + (? * interval '1 second') WHERE id=?",
                lease, settings.leaseSeconds(), id);
        return new Receipt((String) row.get("email"), ((Timestamp) row.get("changed_at")).toInstant(),
                (String) row.get("source"), (String) row.get("ip_address"), (String) row.get("browser"),
                (String) row.get("device"), (String) row.get("user_agent"), ((Number) row.get("attempts")).intValue(), lease);
    }

    private record Receipt(String email, Instant changedAt, String source, String ip, String browser,
                           String device, String userAgent, int attempts, UUID lease) {}
}
