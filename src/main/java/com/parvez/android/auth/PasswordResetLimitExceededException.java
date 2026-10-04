package com.parvez.android.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/** Safe quota response for a valid recovery token, with a calendar-month retry delay. */
public class PasswordResetLimitExceededException extends ResponseStatusException {
    private final HttpHeaders headers;

    public PasswordResetLimitExceededException(int limit, Instant now, Instant resetsAt) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "You can reset your password at most " + limit + " times per UTC calendar month");
        var delay = Duration.between(now, resetsAt);
        long seconds = delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        var values = new HttpHeaders();
        values.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, seconds)));
        headers = HttpHeaders.readOnlyHttpHeaders(values);
    }

    @Override public HttpHeaders getHeaders() { return headers; }
}
