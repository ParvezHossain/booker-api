package com.parvez.android.document;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Safe overload response shared by early upload admission and parser admission. */
final class PdfCapacityException extends ResponseStatusException {
    private final HttpHeaders headers = new HttpHeaders();

    PdfCapacityException(String message, int retrySeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
        headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(retrySeconds));
    }

    @Override public HttpHeaders getHeaders() { return headers; }
}
