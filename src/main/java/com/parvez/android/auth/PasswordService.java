package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.Locale;

/** Password workflows and atomic session revocation, independent of email transport. */
@Service
public class PasswordService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final PasswordResetDelivery delivery;
    private final PasswordChangeNotifications notifications;
    private final Duration ttl;
    private final int monthlyLimit;

    public PasswordService(JdbcTemplate jdbc, PasswordEncoder passwords, PasswordResetDelivery delivery,
            PasswordChangeNotifications notifications,
            @Value("${app.password-reset.ttl:PT30M}") Duration ttl,
            @Value("${app.password-reset.monthly-limit:3}") int monthlyLimit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.delivery = delivery;
        this.notifications = notifications;
        this.ttl = ttl;
        this.monthlyLimit = monthlyLimit;
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Password reset lifetime must be positive and at most 24 hours");
        }
        if (monthlyLimit < 1) {
            throw new IllegalArgumentException("Password reset monthly limit must be positive");
        }
    }

    @Transactional
    public void change(String email, String currentPassword, String newPassword, PasswordChangeContext context) {
        var hashes = jdbc.queryForList("SELECT password_hash FROM workspace_users WHERE email = ? FOR UPDATE", String.class, email);
        if (hashes.isEmpty() || !passwords.matches(currentPassword, hashes.getFirst()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        replace(email, newPassword);
        notifications.record(email, PasswordChangeNotifications.Source.CHANGE, context);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void forgot(String input) {
        if (!delivery.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset email is not configured");
        }
        String email = input.strip().toLowerCase(Locale.ROOT);
        var users = jdbc.queryForList("SELECT email FROM workspace_users WHERE email = ? AND email_verified FOR UPDATE", String.class, email);
        if (users.isEmpty()) return;
        // Preserve the generic public response without issuing unusable recovery emails.
        if (monthlyUsage(email).used() >= monthlyLimit) return;
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
        delivery.enqueue(email, token, issued.getFirst());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reset(String token, String newPassword, PasswordChangeContext context) {
        String digest = OpaqueTokens.sha256Hex(token);
        var users = jdbc.queryForList("SELECT email FROM password_reset_tokens WHERE token_hash = ?", String.class, digest);
        if (users.isEmpty()) throw invalidToken();
        String email = users.getFirst();
        // Same lock order as login, refresh, change and forgot, preventing token issuance races.
        if (jdbc.queryForList("SELECT email FROM workspace_users WHERE email = ? AND email_verified FOR UPDATE", email).isEmpty()) throw invalidToken();
        var usage = monthlyUsage(email);
        int consumed = jdbc.update("DELETE FROM password_reset_tokens WHERE email = ? AND token_hash = ? AND expires_at > ?",
                email, digest, Timestamp.from(usage.checkedAt()));
        if (consumed != 1) throw invalidToken();
        // Validate the token before revealing account-specific quota information. Any
        // rejection rolls back consumption, leaving the password and sessions intact.
        if (usage.used() >= monthlyLimit) {
            throw new PasswordResetLimitExceededException(monthlyLimit, usage.checkedAt(), usage.resetsAt());
        }
        replace(email, newPassword);
        jdbc.update("INSERT INTO password_reset_history (email, reset_at) VALUES (?, ?)",
                email, Timestamp.from(usage.checkedAt()));
        notifications.record(email, PasswordChangeNotifications.Source.RESET, context);
    }

    private MonthlyUsage monthlyUsage(String email) {
        // Call only while holding the account lock. A waiter may cross a month
        // boundary, so use database time after locking, rather than transaction now().
        Instant checkedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        var start = checkedAt.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC);
        Instant resetsAt = start.plusMonths(1).toInstant();
        int used = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1 FROM password_reset_history
                    WHERE email = ? AND reset_at >= ? AND reset_at < ? LIMIT ?
                ) monthly_resets
                """, Integer.class, email, Timestamp.from(start.toInstant()), Timestamp.from(resetsAt), monthlyLimit);
        return new MonthlyUsage(checkedAt, resetsAt, used);
    }

    private record MonthlyUsage(Instant checkedAt, Instant resetsAt, int used) {}

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
