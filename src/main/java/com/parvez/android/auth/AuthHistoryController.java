package com.parvez.android.auth;

import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@Tag(name = "Account security history")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
@ApiResponse(responseCode = "400", description = "Invalid limit/UUID or cursor unavailable for this history/scope",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Workspace or Super Admin permission required; filter rejections may have a separate body", content = @Content)
public class AuthHistoryController {
    private final AuthHistoryService history;
    public AuthHistoryController(AuthHistoryService history) { this.history = history; }

    @GetMapping("/api/workspace/login-history")
    @Operation(summary = "Get your workspace's login history", description = "Successful credential logins across accounts in the authenticated workspace only. No workspace/email selector is accepted. Super Admin has no workspace and receives 403. Newest first with UUID cursor pagination: limit defaults to 50 (1–100); use nextCursor for the next page. Each entry includes email, workspace, UTC timestamp, connection IP and bounded untrusted browser/device/User-Agent. Failed logins, refreshes and ordinary Basic-authenticated requests are excluded. Responses are no-store.")
    @ApiResponse(responseCode = "200", description = "Workspace login page", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthHistoryService.LoginPage.class)))
    public ResponseEntity<AuthHistoryService.LoginPage> ownLogins(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                                                                 @RequestParam(required = false) UUID cursor) {
        return response(history.ownLogins(limit, cursor));
    }

    @GetMapping("/api/workspace/password-change-history")
    @Operation(summary = "Get your workspace's password change history", description = "Committed CHANGE and RESET events across accounts in the authenticated workspace only. No workspace/email selector is accepted. Super Admin receives 403. Newest first with UUID cursor pagination: limit defaults to 50 (1–100); nextCursor is null at the end. Entries include account/workspace, UTC timestamp and request metadata. Invalid, failed and rolled-back operations are excluded; email delivery does not delete history. Responses are no-store.")
    @ApiResponse(responseCode = "200", description = "Workspace password change page", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthHistoryService.PasswordChangePage.class)))
    public ResponseEntity<AuthHistoryService.PasswordChangePage> ownPasswordChanges(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                                                                                  @RequestParam(required = false) UUID cursor) {
        return response(history.ownPasswordChanges(limit, cursor));
    }

    @GetMapping("/api/admin/login-history")
    @Operation(summary = "Get all account login history as Super Admin", description = "SUPER_ADMIN only, enforced by the security filter and service. Lists all workspaces plus Super Admin accounts with null workspace. Optional workspaceId restricts to one workspace; unknown workspace returns an empty page. Limit defaults to 50 (1–100); cursor must belong to this history and selected scope. Newest first, no-store. Workspace users receive 403 and cannot choose a different workspace through their own history API.")
    @ApiResponse(responseCode = "200", description = "Administrator login page", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthHistoryService.LoginPage.class)))
    public ResponseEntity<AuthHistoryService.LoginPage> allLogins(@RequestParam(required = false) UUID workspaceId,
                                                                 @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                                                                 @RequestParam(required = false) UUID cursor) {
        return response(history.allLogins(workspaceId, limit, cursor));
    }

    @GetMapping("/api/admin/password-change-history")
    @Operation(summary = "Get all password change history as Super Admin", description = "SUPER_ADMIN only, enforced by the security filter and service. Lists committed CHANGE/RESET events from all workspaces and Super Admin accounts. Optional workspaceId restricts to one workspace; unknown workspace returns an empty page. Limit defaults to 50 (1–100); cursor must belong to this history and selected scope. Newest first, no-store. Password hashes, credentials, reset/refresh tokens and email ciphertext are never returned.")
    @ApiResponse(responseCode = "200", description = "Administrator password change page", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthHistoryService.PasswordChangePage.class)))
    public ResponseEntity<AuthHistoryService.PasswordChangePage> allPasswordChanges(@RequestParam(required = false) UUID workspaceId,
                                                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                                                                                  @RequestParam(required = false) UUID cursor) {
        return response(history.allPasswordChanges(workspaceId, limit, cursor));
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
