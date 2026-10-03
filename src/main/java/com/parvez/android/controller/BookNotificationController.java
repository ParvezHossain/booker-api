package com.parvez.android.controller;

import com.parvez.android.notification.BookEventStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "Book notifications", description = "Workspace-scoped server-sent events")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
public class BookNotificationController {
    private final BookEventStream stream;

    public BookNotificationController(BookEventStream stream) {
        this.stream = stream;
    }

    @Operation(summary = "Subscribe to new book notifications",
            description = "Streams your workspace's book.created and public-book-request.reviewed events. Omit Last-Event-ID for future events or pass 0 to replay all retained events for your workspace. The first ready event supplies a resume cursor. IDs are global opaque decimal strings and may have gaps. Persist the last processed ID and reconnect with it; handle duplicates idempotently. Heartbeats occur about every 15 seconds; connections expire after five minutes. Reconnect with backoff after closure or 503. Browser clients need a fetch-based SSE client to send Authorization; Swagger Try it out is not a streaming viewer.")
    @ApiResponse(responseCode = "200", description = "SSE stream: ready, book.created, public-book-request.reviewed and heartbeat comments", content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"), examples = @ExampleObject(value = "id: 17\nevent: ready\nretry: 3000\ndata: {}\n\n")))
    @ApiResponse(responseCode = "400", description = "Cursor is malformed, negative, or beyond the latest global event ID", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid owner credentials", content = @Content)
    @ApiResponse(responseCode = "503", description = "Connection capacity reached or notification service stopping", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping(value = "/api/books/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @Parameter(
                    description = "Last successfully handled event ID; omit for future events only, or use 0 for replay",
                    example = "0",
                    schema = @Schema(type = "string",
                            pattern = "^[0-9]+$"))
            @RequestHeader(value = "Last-Event-ID",
                    required = false) String cursor
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store")
                .header("X-Accel-Buffering", "no")
                .body(stream.subscribe(cursor, com.parvez.android.saas.WorkspacePrincipal.currentWorkspace()));
    }
}
