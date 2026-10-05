package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetQuotaTest {
    @Test void rejectsNonpositiveMonthlyLimitsAtStartup() {
        for (int limit : List.of(0, -1)) {
            var error = assertThrows(IllegalArgumentException.class, () -> new PasswordService(
                    mock(JdbcTemplate.class), mock(PasswordEncoder.class), mock(PasswordResetDelivery.class), mock(PasswordChangeNotifications.class),
                    Duration.ofMinutes(30), limit));
            assertEquals("Password reset monthly limit must be positive", error.getMessage());
        }
    }

    @Test void postLockDatabaseTimeSetsMonthAndHistoryInstantAcrossYearRollover() {
        var jdbc = mock(JdbcTemplate.class);
        var encoder = mock(PasswordEncoder.class);
        String email = "reader@example.com";
        String token = "A".repeat(43);
        String digest = OpaqueTokens.sha256Hex(token);
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        when(jdbc.queryForList("SELECT email FROM password_reset_tokens WHERE token_hash = ?", String.class, digest))
                .thenReturn(List.of(email));
        when(jdbc.queryForList("SELECT email FROM workspace_users WHERE email = ? AND email_verified FOR UPDATE", email))
                .thenReturn(List.of(java.util.Map.of("email", email)));
        when(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).thenReturn(Timestamp.from(now));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(email), any(Timestamp.class), any(Timestamp.class), eq(3)))
                .thenReturn(2);
        when(jdbc.update("DELETE FROM password_reset_tokens WHERE email = ? AND token_hash = ? AND expires_at > ?",
                email, digest, Timestamp.from(now))).thenReturn(1);
        new PasswordService(jdbc, encoder, mock(PasswordResetDelivery.class), mock(PasswordChangeNotifications.class), Duration.ofMinutes(30), 3).reset(token, "new-password-123", PasswordChangeContext.unknown());
        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).queryForList("SELECT email FROM workspace_users WHERE email = ? AND email_verified FOR UPDATE", email);
        ordered.verify(jdbc).queryForObject("SELECT clock_timestamp()", Timestamp.class);
        ordered.verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq(email), eq(Timestamp.from(now)),
                eq(Timestamp.from(Instant.parse("2027-02-01T00:00:00Z"))), eq(3));
        verify(jdbc).update("INSERT INTO password_reset_history (email, reset_at) VALUES (?, ?)", email, Timestamp.from(now));
    }

    @Test void retryAfterRoundsUpAtLeapMonthBoundary() {
        var error = new PasswordResetLimitExceededException(3,
                Instant.parse("2028-02-29T23:59:59.100Z"), Instant.parse("2028-03-01T00:00:00Z"));
        assertEquals(429, error.getStatusCode().value());
        assertEquals("1", error.getHeaders().getFirst("Retry-After"));
    }
}
