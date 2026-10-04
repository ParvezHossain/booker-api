package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Locale;

/** Password workflows and atomic session revocation, independent of email transport. */
@Service
public class PasswordService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final PasswordResetDelivery delivery;
    private final Duration ttl;

    public PasswordService(JdbcTemplate jdbc, PasswordEncoder passwords, PasswordResetDelivery delivery,
            @Value("${app.password-reset.ttl:PT30M}") Duration ttl) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.delivery = delivery;
        this.ttl = ttl;
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Password reset lifetime must be positive and at most 24 hours");
        }
    }

    @Transactional
    public void change(String email, String currentPassword, String newPassword) {
        var hashes = jdbc.queryForList("SELECT password_hash FROM workspace_users WHERE email = ? FOR UPDATE", String.class, email);
        if (hashes.isEmpty() || !passwords.matches(currentPassword, hashes.getFirst()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        replace(email, newPassword);
    }

    @Transactional
    public void forgot(String input) {
        if (!delivery.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset email is not configured");
        }
        String email = input.strip().toLowerCase(Locale.ROOT);
        var users = jdbc.queryForList("SELECT email FROM workspace_users WHERE email = ? FOR UPDATE", String.class, email);
        if (users.isEmpty()) return;
        String token = OpaqueTokens.random();
        var issued = jdbc.query("""
                INSERT INTO password_reset_tokens (email, token_hash, expires_at)
                VALUES (?, ?, now() + (? * interval '1 second'))
                ON CONFLICT (email) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                    expires_at = EXCLUDED.expires_at, requested_at = now()
                WHERE password_reset_tokens.requested_at <= now() - interval '60 seconds'
                RETURNING expires_at
                """, (rs, row) -> rs.getTimestamp("expires_at").toInstant(),
                email, OpaqueTokens.sha256Hex(token), ttl.toSeconds());
        if (issued.isEmpty()) return;
        if (!delivery.send(email, token, issued.getFirst())) {
            // Keep the issuance timestamp for the cooldown, but make an undelivered token unusable.
            jdbc.update("UPDATE password_reset_tokens SET expires_at = now() WHERE email = ?", email);
        }
    }

    @Transactional
    public void reset(String token, String newPassword) {
        String digest = OpaqueTokens.sha256Hex(token);
        var users = jdbc.queryForList("SELECT email FROM password_reset_tokens WHERE token_hash = ?", String.class, digest);
        if (users.isEmpty()) throw invalidToken();
        String email = users.getFirst();
        // Same lock order as login, refresh, change and forgot, preventing token issuance races.
        jdbc.queryForList("SELECT email FROM workspace_users WHERE email = ? FOR UPDATE", email);
        int consumed = jdbc.update("DELETE FROM password_reset_tokens WHERE email = ? AND token_hash = ? AND expires_at > now()", email, digest);
        if (consumed != 1) throw invalidToken();
        replace(email, newPassword);
    }

    private void replace(String email, String password) {
        jdbc.update("UPDATE workspace_users SET password_hash = ?, credential_version = credential_version + 1 WHERE email = ?",
                passwords.encode(password), email);
        jdbc.update("DELETE FROM refresh_tokens WHERE email = ?", email);
        jdbc.update("DELETE FROM password_reset_tokens WHERE email = ?", email);
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token");
    }
}
