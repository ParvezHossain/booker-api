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
@EnableConfigurationProperties(EmailActivationEmailSettings.class)
public class EmailActivationEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailActivationEmailWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EmailActivationCipher cipher;
    private final SmtpEmailActivationDelivery smtp;
    private final EmailActivationEmailSettings settings;

    public EmailActivationEmailWorker(JdbcTemplate jdbc, TransactionTemplate transaction, EmailActivationCipher cipher,
            SmtpEmailActivationDelivery smtp, EmailActivationEmailSettings settings) {
        this.jdbc = jdbc; this.transaction = transaction; this.cipher = cipher; this.smtp = smtp; this.settings = settings;
    }

    public void deliver(UUID id) {
        var receipt = transaction.execute(status -> claim(id));
        if (receipt == null) return;
        boolean sent = false;
        try {
            String token = cipher.decrypt(receipt.encrypted(), id, receipt.email(), receipt.hash(), receipt.expiresAt());
            if (!token.matches("[A-Za-z0-9_-]{43}") || !OpaqueTokens.sha256Hex(token).equals(receipt.hash()))
                throw new IllegalStateException("Invalid queued activation token");
            // Recheck immediately before SMTP. Replacement during an in-flight SMTP
            // send can still deliver old mail, but must never restore the old token.
            if (isCurrent(receipt)) sent = smtp.send(receipt.email(), token, receipt.expiresAt(), receipt.workspaceName());
            else {
                transaction.executeWithoutResult(status -> jdbc.update(
                        "DELETE FROM email_activation_emails WHERE id=? AND lease_id=?", id, receipt.lease()));
                return;
            }
        } catch (RuntimeException failure) {
            log.warn("Email activation email could not be prepared; receipt scheduled for bounded retry");
        }
        boolean delivered = sent;
        transaction.executeWithoutResult(status -> {
            if (delivered) {
                jdbc.update("DELETE FROM email_activation_emails WHERE id=? AND lease_id=?", id, receipt.lease());
            } else {
                int attempts = receipt.attempts() + 1;
                long delay = Math.min(3600L, settings.retrySeconds() * (1L << Math.max(0, attempts - 1)));
                jdbc.update("""
                        UPDATE email_activation_emails SET attempts=?, published_at=NULL,
                            available_at=clock_timestamp() + (? * interval '1 second'),
                            failed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                            lease_id=NULL, lease_until=NULL WHERE id=? AND lease_id=?
                        """, attempts, delay, attempts >= settings.maxAttempts(), id, receipt.lease());
                log.warn("Email activation email delivery failed; receipt {}",
                        attempts >= settings.maxAttempts() ? "parked until expiry/operator review" : "scheduled for delayed retry");
            }
        });
    }

    private Receipt claim(UUID id) {
        var rows = jdbc.queryForList("""
                SELECT e.*, w.name AS workspace_name FROM email_activation_emails e
                    JOIN workspace_users u ON u.email=e.email JOIN workspaces w ON w.id=u.workspace_id
                    WHERE e.id=? AND failed_at IS NULL
                    AND available_at <= clock_timestamp()
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp()) FOR UPDATE OF e
                """, id);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var receipt = new Receipt(id, (String) row.get("email"), (String) row.get("token_hash"),
                (String) row.get("encrypted_token"), ((Timestamp) row.get("expires_at")).toInstant(),
                ((Number) row.get("attempts")).intValue(), UUID.randomUUID(), (String) row.get("workspace_name"));
        if (!isCurrent(receipt)) {
            jdbc.update("DELETE FROM email_activation_emails WHERE id=?", id);
            return null;
        }
        if (!cipher.isConfigured() || !smtp.isConfigured()) {
            jdbc.update("UPDATE email_activation_emails SET published_at=NULL, available_at=clock_timestamp() + (? * interval '1 second') WHERE id=?",
                    settings.retrySeconds(), id);
            return null;
        }
        jdbc.update("UPDATE email_activation_emails SET lease_id=?, lease_until=clock_timestamp() + (? * interval '1 second') WHERE id=?",
                receipt.lease(), settings.leaseSeconds(), id);
        return receipt;
    }

    private boolean isCurrent(Receipt receipt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM email_activation_tokens t JOIN workspace_users u ON u.email=t.email
                    WHERE t.email=? AND t.token_hash=? AND t.expires_at > clock_timestamp() AND NOT u.email_verified)
                    AND ? > clock_timestamp()
                """, Boolean.class, receipt.email(), receipt.hash(), Timestamp.from(receipt.expiresAt())));
    }

    private record Receipt(UUID id, String email, String hash, String encrypted, Instant expiresAt, int attempts, UUID lease, String workspaceName) {}
}
