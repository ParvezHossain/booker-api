package com.parvez.android.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Calendar-month quota backed by committed history and a workspace row lock. */
@Component
public class BookRequestRateLimiter {
    public static final int MONTHLY_LIMIT = 10;
    private final JdbcTemplate jdbc;

    public BookRequestRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant check(UUID workspace) {
        // Held until the request and its email receipts commit or roll back.
        jdbc.queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE", UUID.class, workspace);
        // Read time after acquiring the lock, not at transaction start: a waiter
        // can cross a month boundary. Persist this same instant on the new request.
        Instant submittedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        var start = submittedAt.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC);
        Instant resetsAt = start.plusMonths(1).toInstant();
        long used = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1 FROM public_library_book_requests
                    WHERE workspace_id=? AND created_at >= ? AND created_at < ? LIMIT ?
                ) monthly_requests
                """, Long.class, workspace, Timestamp.from(start.toInstant()), Timestamp.from(resetsAt), MONTHLY_LIMIT);
        if (used >= MONTHLY_LIMIT) throw new BookRequestLimitExceededException(submittedAt, resetsAt);
        return submittedAt;
    }
}
