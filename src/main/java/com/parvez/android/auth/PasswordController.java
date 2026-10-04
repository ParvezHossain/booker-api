package com.parvez.android.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class PasswordController {
    private final PasswordService passwords;
    public PasswordController(PasswordService passwords) { this.passwords = passwords; }

    @ApiResponse(responseCode = "204", description = "Password changed; all account tokens revoked", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid input or current password", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @PostMapping("/change-password")
    @Operation(summary = "Change your password", description = "Requires the current password. Returns 204; revokes all access/refresh/reset tokens. Invalid current password or input: 400; unauthenticated: 401.",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "basicAuth")})
    public ResponseEntity<Void> change(Authentication authentication, @Valid @RequestBody Change request) {
        passwords.change(authentication.getName(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ApiResponse(responseCode = "202", description = "Generic response after durable email acceptance, without waiting for SMTP or RabbitMQ", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Invalid email", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "SMTP/sender or queued-token encryption key not configured", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset email", description = "Public. Returns generic 202 after token and encrypted email receipt commit, without SMTP or RabbitMQ I/O on the request thread. Same response for unknown accounts, cooldown and exhausted monthly allowance. Background workers publish opaque receipt IDs to RabbitMQ and retry SMTP with the original expiry; expired/replaced/consumed tokens are skipped. Delivery is limited to once per account per minute. Successful resets are limited per account per UTC calendar month, default 3 via PASSWORD_RESET_MONTHLY_LIMIT; requesting email does not consume allowance. Invalid input: 400; SMTP/sender or PASSWORD_RESET_EMAIL_ENCRYPTION_KEY missing: 503. Broker/provider outages after acceptance do not change 202. Token expires after 30 minutes from issuance by default; token never returned by API. With no PASSWORD_RESET_URL, email contains a token to copy into reset-password in Swagger or the app. A configured HTTPS URL sends a reset link instead.")
    public ResponseEntity<Message> forgot(@Valid @RequestBody Forgot request) {
        passwords.forgot(request.email());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new Message("If the account exists, a password reset email will be sent."));
    }

    @ApiResponse(responseCode = "204", description = "Password reset; all account tokens revoked", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid input or expired, invalid or used token", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "Valid token but monthly successful-reset allowance exhausted",
            headers = @Header(name = "Retry-After", description = "Seconds until the next UTC calendar month, rounded up", schema = @Schema(type = "integer", format = "int64")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/reset-password")
    @Operation(summary = "Reset password with an emailed token", description = "Public. Returns 204. A valid unexpired token is single use. At most 3 successful resets per account per UTC calendar month by default, configurable via PASSWORD_RESET_MONTHLY_LIMIT. Invalid/expired/replayed token or invalid input: 400. Valid token at the limit: 429 with Retry-After seconds until the next month; password, sessions and token remain unchanged. Only committed successful resets consume allowance; authenticated change-password is excluded. Revokes all existing access, refresh and reset tokens on success; log in again.")
    public ResponseEntity<Void> reset(@Valid @RequestBody Reset request) {
        passwords.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Schema(name = "ChangePasswordRequest")
    public record Change(@Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(max=64) String currentPassword,
                         @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(min=12, max=64) String newPassword) {}
    @Schema(name = "ForgotPasswordRequest")
    public record Forgot(@Schema(example = "owner@example.com") @NotBlank @Email @Size(max=254) String email) {}
    @Schema(name = "ResetPasswordRequest")
    public record Reset(@Schema(description = "Single-use token copied from the email or its reset link", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
                        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(min=12, max=64) String newPassword) {}
    public record Message(String message) {}
}
