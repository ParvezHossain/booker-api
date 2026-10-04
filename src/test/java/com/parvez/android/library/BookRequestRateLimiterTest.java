package com.parvez.android.library;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookRequestRateLimiterTest {
    @Test void usesPostLockDatabaseTimeAcrossYearRollover() {
        var jdbc=mock(JdbcTemplate.class);var workspace=UUID.randomUUID();
        Instant now=Instant.parse("2027-01-01T00:00:00Z");
        when(jdbc.queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE",UUID.class,workspace)).thenReturn(workspace);
        when(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).thenReturn(Timestamp.from(now));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(workspace),any(Timestamp.class),any(Timestamp.class),eq(10))).thenReturn(9L);
        assertEquals(now,new BookRequestRateLimiter(jdbc).check(workspace));
        var ordered=inOrder(jdbc);
        ordered.verify(jdbc).queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE",UUID.class,workspace);
        ordered.verify(jdbc).queryForObject("SELECT clock_timestamp()",Timestamp.class);
        ordered.verify(jdbc).queryForObject(anyString(),eq(Long.class),eq(workspace),eq(Timestamp.from(now)),eq(Timestamp.from(Instant.parse("2027-02-01T00:00:00Z"))),eq(10));
    }
    @Test void retryAfterRoundsUpFractionalSecondsAtLeapMonthBoundary() {
        var exception=new BookRequestLimitExceededException(Instant.parse("2028-02-29T23:59:59.100Z"),Instant.parse("2028-03-01T00:00:00Z"));
        assertEquals("1",exception.getHeaders().getFirst("Retry-After"));
        assertEquals(429,exception.getStatusCode().value());
    }
}
