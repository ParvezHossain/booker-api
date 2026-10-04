package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Atomic encrypted outbox insertion, with no SMTP or broker I/O on the HTTP thread. */
@Component
public class QueuedPasswordResetDelivery implements PasswordResetDelivery {
    private final JdbcTemplate jdbc;
    private final PasswordResetEmailCipher cipher;
    private final SmtpPasswordResetDelivery smtp;

    public QueuedPasswordResetDelivery(JdbcTemplate jdbc, PasswordResetEmailCipher cipher, SmtpPasswordResetDelivery smtp) {
        this.jdbc = jdbc; this.cipher = cipher; this.smtp = smtp;
    }

    @Override public boolean isConfigured() { return cipher.isConfigured() && smtp.isConfigured(); }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String email, String token, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String hash = OpaqueTokens.sha256Hex(token);
        String encrypted = cipher.encrypt(token, id, email, hash, expiresAt);
        jdbc.update("""
                INSERT INTO password_reset_emails (id, email, token_hash, encrypted_token, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, email, hash, encrypted, Timestamp.from(expiresAt));
    }
}
