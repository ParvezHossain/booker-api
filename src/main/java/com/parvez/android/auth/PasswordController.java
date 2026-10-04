package com.parvez.android.auth;

import io.swagger.v3.oas.annotations.Operation;
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

    @ApiResponse(responseCode = "202", description = "Generic response regardless of account existence or delivery result", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Invalid email", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Email delivery not configured", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset email", description = "Public. Always returns the same 202 message for registered/unregistered emails. Delivery is limited to once per account per minute. Invalid input: 400; email not configured: 503. Token expires after 30 minutes by default; token never returned by API. With no PASSWORD_RESET_URL, email contains a token to copy into reset-password in Swagger or the app. A configured HTTPS URL sends a reset link instead.")
    public ResponseEntity<Message> forgot(@Valid @RequestBody Forgot request) {
        passwords.forgot(request.email());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new Message("If the account exists, a password reset email will be sent."));
    }

    @ApiResponse(responseCode = "204", description = "Password reset; all account tokens revoked", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid input or expired, invalid or used token", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/reset-password")
    @Operation(summary = "Reset password with an emailed token", description = "Public. Returns 204. A valid unexpired token is single use. Invalid/expired/replayed token or invalid input: 400. Revokes all existing access, refresh and reset tokens; log in again.")
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
