package com.parvez.android.library;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;

public class BookRequestLimitExceededException extends ResponseStatusException {
    private final HttpHeaders headers;

    public BookRequestLimitExceededException(Instant now, Instant resetsAt) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Your workspace can submit at most 10 book requests per UTC calendar month");
        var delay = Duration.between(now, resetsAt);
        long seconds = delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        var values = new HttpHeaders();
        values.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, seconds)));
        headers = HttpHeaders.readOnlyHttpHeaders(values);
    }

    @Override public HttpHeaders getHeaders() { return headers; }
}
