package com.parvez.android.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {
    private final TokenService tokens;
    public AuthController(TokenService tokens) { this.tokens = tokens; }

    @PostMapping("/login")
    public ResponseEntity<TokenService.Tokens> login(@Valid @RequestBody Login request) {
        return response(tokens.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenService.Tokens> refresh(@Valid @RequestBody Refresh request) {
        return response(tokens.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody Refresh request) {
        tokens.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<TokenService.Tokens> response(TokenService.Tokens result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(result);
    }

    public record Login(@NotBlank @Email @Size(max=254) String email,
                        @NotBlank @Size(max=64) String password) {}
    public record Refresh(@NotBlank @Size(max=4096) String refreshToken) {}
}
