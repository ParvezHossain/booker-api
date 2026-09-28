package com.parvez.android.drive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Google Drive")
public class GoogleDriveController {
    private static final String CALLBACK = "/api/integrations/google-drive/callback";
    private final GoogleDriveConnectionService connections;
    private final GoogleDriveImportService imports;
    public GoogleDriveController(GoogleDriveConnectionService connections, GoogleDriveImportService imports) { this.connections = connections; this.imports = imports; }
    @Operation(summary = "Start Google OAuth", description = "Returns an authorization URL and sets an HttpOnly browser-binding cookie. Open the URL in the same browser. Native clients must retain the cookie for their browser-based flow. Requests only drive.file.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @PostMapping("/api/integrations/google-drive/connect")
    public ResponseEntity<Map<String, String>> connect(HttpServletRequest request) {
        var result = connections.begin();
        var cookie = ResponseCookie.from("booker_drive_binding", result.binding()).httpOnly(true).secure(request.isSecure())
                .sameSite("Lax").path(CALLBACK).maxAge(Duration.ofMinutes(10)).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookie.toString()).body(Map.of("authorizationUrl", result.url()));
    }
    @Operation(summary = "OAuth callback", description = "Google browser callback; validated one-time state and browser binding replace API authentication here. No Google credential is returned.", security = {})
    @GetMapping(value = CALLBACK, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @CookieValue(name = "booker_drive_binding", required = false) String binding) {
        connections.complete(state, binding, code);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, ResponseCookie.from("booker_drive_binding", "").path(CALLBACK).httpOnly(true).sameSite("Lax").maxAge(0).build().toString())
                .body("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Google Drive connected</title><p>Google Drive is connected. Close this tab and return to your bookshelf.</p></html>");
    }
    @Operation(summary = "Check Google Drive connection")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @GetMapping("/api/integrations/google-drive/connection")
    public ResponseEntity<Map<String, Boolean>> connection() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("connected", connections.connected())); }
    @Operation(summary = "Disconnect Google Drive", description = "Deletes local OAuth credentials and cancels pending imports. Already imported PDFs remain available; an import already running may finish. Google account consent can also be revoked in Google account settings.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @DeleteMapping("/api/integrations/google-drive/connection")
    public ResponseEntity<Void> disconnect() { connections.disconnect(); return ResponseEntity.noContent().build(); }
    @Operation(summary = "Get short-lived Google Picker configuration", description = "Contains a selected-file scoped access token and a public restricted Picker API key. Never includes refresh tokens or OAuth client secrets; keep this response in memory only.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @GetMapping("/api/integrations/google-drive/picker")
    public ResponseEntity<Map<String, String>> picker() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connections.picker()); }
    @Operation(summary = "Import a selected Drive PDF", description = "Returns 202 and a durable import operation. Idempotency-Key is a UUID. Only Drive file IDs are accepted, never URLs. Imports use the connected account and the same PDF validation/storage pipeline as uploads.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @ApiResponse(responseCode = "202", description = "Import accepted; poll Location for status")
    @PostMapping("/api/books/{bookId}/document/imports/google-drive")
    public ResponseEntity<GoogleDriveImportService.ImportStatus> start(@PathVariable long bookId, @RequestHeader("Idempotency-Key") UUID operation, @Valid @RequestBody Import request) {
        return ResponseEntity.accepted().location(URI.create("/api/books/" + bookId + "/document/imports/" + operation)).body(imports.start(bookId, operation, request.fileId()));
    }
    @Operation(summary = "Get your Drive import status")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @GetMapping("/api/books/{bookId}/document/imports/{importId}")
    public GoogleDriveImportService.ImportStatus status(@PathVariable long bookId, @PathVariable UUID importId) { return imports.status(bookId, importId); }
    public record Import(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,200}") String fileId) {}
}
