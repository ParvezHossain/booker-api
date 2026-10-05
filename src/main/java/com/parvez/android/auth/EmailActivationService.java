package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Account-locked single-use activation and encrypted durable issuance, without network I/O. */
@Service
public class EmailActivationService {
    private final JdbcTemplate jdbc;
    private final EmailActivationCipher cipher;
    private final Duration ttl;

    public EmailActivationService(JdbcTemplate jdbc, EmailActivationCipher cipher,
            @Value("${app.email-activation.ttl:PT24H}") Duration ttl) {
        this.jdbc = jdbc; this.cipher = cipher; this.ttl = ttl;
        if (ttl.compareTo(Duration.ofMinutes(1)) < 0 || ttl.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("Email activation lifetime must be between one minute and seven days");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant issue(String email) {
        if (!cipher.isConfigured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email activation is not configured");
        // The caller owns the newly inserted account or its existing row lock.
        Instant expiresAt = jdbc.queryForObject("SELECT clock_timestamp() + (? * interval '1 second')",
                Timestamp.class, ttl.toSeconds()).toInstant();
        String token = OpaqueTokens.random();
        String hash = OpaqueTokens.sha256Hex(token);
        jdbc.update("""
                INSERT INTO email_activation_tokens(email,token_hash,expires_at)
                VALUES (?,?,?) ON CONFLICT(email) DO UPDATE SET token_hash=EXCLUDED.token_hash,
                    expires_at=EXCLUDED.expires_at, requested_at=clock_timestamp()
                """, email, hash, Timestamp.from(expiresAt));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO email_activation_emails(id,email,token_hash,encrypted_token,expires_at)
                VALUES (?,?,?,?,?)
                """, id, email, hash, cipher.encrypt(token, id, email, hash, expiresAt), Timestamp.from(expiresAt));
        return expiresAt;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resend(String input) {
        if (!cipher.isConfigured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email activation is not configured");
        String email = input.strip().toLowerCase(Locale.ROOT);
        var accounts = jdbc.queryForList("SELECT email_verified FROM workspace_users WHERE email=? FOR UPDATE", Boolean.class, email);
        if (accounts.isEmpty() || accounts.getFirst()) return;
        boolean coolingDown = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM email_activation_tokens
                    WHERE email=? AND requested_at > clock_timestamp()-interval '60 seconds')
                """, Boolean.class, email));
        if (!coolingDown) issue(email);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void activate(String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        var accounts = jdbc.queryForList("SELECT email FROM email_activation_tokens WHERE token_hash=?", String.class, hash);
        if (accounts.isEmpty()) throw invalid();
        String email = accounts.getFirst();
        var verified = jdbc.queryForList("SELECT email_verified FROM workspace_users WHERE email=? FOR UPDATE", Boolean.class, email);
        if (verified.isEmpty() || verified.getFirst()) throw invalid();
        int consumed = jdbc.update("DELETE FROM email_activation_tokens WHERE email=? AND token_hash=? AND expires_at > clock_timestamp()", email, hash);
        if (consumed != 1) throw invalid();
        jdbc.update("UPDATE workspace_users SET email_verified=TRUE,email_verified_at=clock_timestamp() WHERE email=?", email);
        // A leased worker cannot recreate a token or finalize another receipt's lease.
        jdbc.update("DELETE FROM email_activation_emails WHERE email=?", email);
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activation token is invalid or expired");
    }
}
