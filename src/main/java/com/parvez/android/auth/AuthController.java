package com.parvez.android.auth;

import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login, rotating refresh tokens and password recovery")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Malformed JSON or invalid request fields",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials or expired/invalid refresh token",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
public class AuthController {
    private final TokenService tokens;

    public AuthController(TokenService tokens) { this.tokens = tokens; }

    @Operation(summary = "Log in with account credentials", security = {},
            description = "Public endpoint for workspace owners and Super Admin. Email is normalized to lowercase. "
                    + "Returns Bearer access and refresh tokens with lifetimes in seconds. Responses are never cached; "
                    + "invalid passwords and unknown emails return the same 401. Successful token issuance atomically records "
                    + "account/workspace, database time, connection IP and bounded optional User-Agent with inferred browser/device. "
                    + "Failed logins, refreshes and ordinary Basic-authenticated requests do not create login history. "
                    + "History is available to the owning workspace and Super Admin through the audit APIs.")
    @ApiResponse(responseCode = "200", description = "Authenticated; store tokens securely",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TokenService.Tokens.class)))
    @PostMapping("/login")
    public ResponseEntity<TokenService.Tokens> login(@Valid @RequestBody Login request, HttpServletRequest servletRequest) {
        return response(tokens.login(request.email(), request.password(), PasswordChangeContext.from(servletRequest)));
    }

    @Operation(summary = "Rotate a refresh token", security = {},
            description = "Public endpoint; refreshToken in the JSON body authenticates the request. "
                    + "Consumes a valid token once and returns a new access/refresh pair. Serialize refresh requests "
                    + "and replace both stored tokens atomically; a concurrent retry with the consumed token returns 401. "
                    + "Password changes invalidate all earlier sessions.")
    @ApiResponse(responseCode = "200", description = "New token pair; previous refresh token is consumed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TokenService.Tokens.class)))
    @PostMapping("/refresh")
    public ResponseEntity<TokenService.Tokens> refresh(@Valid @RequestBody Refresh request) {
        return response(tokens.refresh(request.refreshToken()));
    }

    @Operation(summary = "Revoke a refresh token", security = {},
            description = "Public endpoint; supply the refreshToken in the JSON body. "
                    + "Revokes only that refresh token. A valid signed token already removed from the database returns 204; "
                    + "expired, malformed or access tokens return 401. Existing access tokens remain valid until expiry "
                    + "unless the password changes. Always clear the local client session after logout.")
    @ApiResponse(responseCode = "204", description = "Refresh token revoked; empty response", content = @Content)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody Refresh request) {
        tokens.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<TokenService.Tokens> response(TokenService.Tokens result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(result);
    }

    @Schema(name = "LoginRequest")
    public record Login(@Schema(example = "owner@example.com") @NotBlank @Email @Size(max=254) String email,
                        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
                        @NotBlank @Size(max=64) String password) {}

    @Schema(name = "RefreshRequest")
    public record Refresh(@Schema(description = "The most recently issued refresh token; never an access token",
                                 accessMode = Schema.AccessMode.WRITE_ONLY)
                          @NotBlank @Size(max=4096) String refreshToken) {}
}
