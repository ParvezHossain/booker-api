package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Short database lease transactions surround SMTP; no account/receipt lock spans network I/O. */
@Component
@EnableConfigurationProperties(PasswordResetEmailSettings.class)
public class PasswordResetEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordResetEmailCipher cipher;
    private final SmtpPasswordResetDelivery smtp;
    private final PasswordResetEmailSettings settings;

    public PasswordResetEmailWorker(JdbcTemplate jdbc, TransactionTemplate transaction, PasswordResetEmailCipher cipher,
            SmtpPasswordResetDelivery smtp, PasswordResetEmailSettings settings) {
        this.jdbc = jdbc; this.transaction = transaction; this.cipher = cipher; this.smtp = smtp; this.settings = settings;
    }

    public void deliver(UUID id) {
        var receipt = transaction.execute(status -> claim(id));
        if (receipt == null) return;
        boolean sent = false;
        try {
            String token = cipher.decrypt(receipt.encrypted(), id, receipt.email(), receipt.hash(), receipt.expiresAt());
            if (!token.matches("[A-Za-z0-9_-]{43}") || !OpaqueTokens.sha256Hex(token).equals(receipt.hash()))
                throw new IllegalStateException("Invalid queued reset token");
            // Recheck immediately before SMTP. Replacement during an in-flight SMTP
            // send can still deliver old mail, but must never restore the old token.
            if (isCurrent(receipt)) sent = smtp.send(receipt.email(), token, receipt.expiresAt());
            else {
                transaction.executeWithoutResult(status -> jdbc.update(
                        "DELETE FROM password_reset_emails WHERE id=? AND lease_id=?", id, receipt.lease()));
                return;
            }
        } catch (RuntimeException failure) {
            log.warn("Password reset email could not be prepared; receipt scheduled for bounded retry");
        }
        boolean delivered = sent;
        transaction.executeWithoutResult(status -> {
            if (delivered) {
                jdbc.update("DELETE FROM password_reset_emails WHERE id=? AND lease_id=?", id, receipt.lease());
            } else {
                int attempts = receipt.attempts() + 1;
                long delay = Math.min(3600L, settings.retrySeconds() * (1L << Math.max(0, attempts - 1)));
                jdbc.update("""
                        UPDATE password_reset_emails SET attempts=?, published_at=NULL,
                            available_at=clock_timestamp() + (? * interval '1 second'),
                            failed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                            lease_id=NULL, lease_until=NULL WHERE id=? AND lease_id=?
                        """, attempts, delay, attempts >= settings.maxAttempts(), id, receipt.lease());
                log.warn("Password reset email delivery failed; receipt {}",
                        attempts >= settings.maxAttempts() ? "parked until expiry/operator review" : "scheduled for delayed retry");
            }
        });
    }

    private Receipt claim(UUID id) {
        var rows = jdbc.queryForList("""
                SELECT * FROM password_reset_emails WHERE id=? AND failed_at IS NULL
                    AND available_at <= clock_timestamp()
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp()) FOR UPDATE
                """, id);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var receipt = new Receipt(id, (String) row.get("email"), (String) row.get("token_hash"),
                (String) row.get("encrypted_token"), ((Timestamp) row.get("expires_at")).toInstant(),
                ((Number) row.get("attempts")).intValue(), UUID.randomUUID());
        if (!isCurrent(receipt)) {
            jdbc.update("DELETE FROM password_reset_emails WHERE id=?", id);
            return null;
        }
        if (!cipher.isConfigured() || !smtp.isConfigured()) {
            jdbc.update("UPDATE password_reset_emails SET published_at=NULL, available_at=clock_timestamp() + (? * interval '1 second') WHERE id=?",
                    settings.retrySeconds(), id);
            return null;
        }
        jdbc.update("UPDATE password_reset_emails SET lease_id=?, lease_until=clock_timestamp() + (? * interval '1 second') WHERE id=?",
                receipt.lease(), settings.leaseSeconds(), id);
        return receipt;
    }

    private boolean isCurrent(Receipt receipt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM password_reset_tokens
                    WHERE email=? AND token_hash=? AND expires_at > clock_timestamp())
                    AND ? > clock_timestamp()
                """, Boolean.class, receipt.email(), receipt.hash(), Timestamp.from(receipt.expiresAt())));
    }

    private record Receipt(UUID id, String email, String hash, String encrypted, Instant expiresAt, int attempts, UUID lease) {}
}
