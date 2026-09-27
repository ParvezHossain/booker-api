package com.parvez.android.controller;

import com.parvez.android.notification.BookEventStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@SecurityRequirement(name = "basicAuth")
public class BookNotificationController {
    private final BookEventStream stream;

    public BookNotificationController(BookEventStream stream) {
        this.stream = stream;
    }

    @Operation(summary = "Subscribe to new book notifications",
            description = "SSE stream of book.created events. Pass Last-Event-ID to replay missed events; "
                    + "omit it to receive future events only. Reconnect when the stream closes.")
    @GetMapping(value = "/api/books/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@RequestHeader(value = "Last-Event-ID", required = false) String cursor) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store")
                .header("X-Accel-Buffering", "no")
                .body(stream.subscribe(cursor));
    }
}
