package com.parvez.android.auth;

import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Email activation")
@ApiResponse(responseCode = "400", description = "Invalid request fields or activation token", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
public class EmailActivationController {
    private final EmailActivationService activation;
    public EmailActivationController(EmailActivationService activation) { this.activation = activation; }

    @PostMapping("/activate")
    @Operation(summary = "Activate a workspace owner email", security = {}, description = "Public, single-use activation token in JSON body. Valid for EMAIL_ACTIVATION_TTL (default one day) from issuance. Returns 204; does not log in or issue sessions. Invalid, expired, replaced and consumed tokens return the same 400. Activation does not change the password; log in afterward. GET links never activate accounts.")
    @ApiResponse(responseCode = "204", description = "Email activated; log in with your existing signup password", content = @Content)
    public ResponseEntity<Void> activate(@Valid @RequestBody ActivationRequest request) {
        activation.activate(request.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/resend-activation")
    @Operation(summary = "Request a replacement activation email", security = {}, description = "Public, generic 202 for pending, active and unknown accounts. A pending account may receive a new token after a 60-second server cooldown, replacing all earlier tokens and starting a new configured lifetime. SMTP/RabbitMQ run asynchronously; 202 does not prove delivery. No account/workspace is recreated or password changed.")
    @ApiResponse(responseCode = "202", description = "If eligible, an activation email has been queued", content = @Content)
    @ApiResponse(responseCode = "503", description = "Activation encryption is not configured", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> resend(@Valid @RequestBody ResendRequest request) {
        activation.resend(request.email());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @Schema(name = "EmailActivationRequest")
    public record ActivationRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}")
                                    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "43-character email activation token; never a password reset token") String token) {}
    @Schema(name = "ResendActivationRequest")
    public record ResendRequest(@NotBlank @Email @Size(max = 254) @Schema(example = "owner@example.com") String email) {}
}
