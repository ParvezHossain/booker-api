package com.parvez.android.auth;

import com.parvez.android.security.OpaqueTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Public copy helper; token fragments are handled in the browser, never by this controller. */
@RestController
@Tag(name = "Authentication")
public class PasswordResetTokenPageController {
    private final String template;

    public PasswordResetTokenPageController() throws IOException {
        template = new ClassPathResource("templates/password-reset-token.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/password-reset-token", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Open the password reset token copy page", security = {},
            description = "Public HTML helper. Reads #token in the browser and removes it from browser history. "
                    + "Does not validate, consume or retrieve reset tokens. Copying requires a user action.")
    @ApiResponse(responseCode = "200", description = "Token copy page", content = @Content(mediaType = "text/html"))
    public ResponseEntity<String> page() {
        String nonce = OpaqueTokens.random();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("Permissions-Policy", "clipboard-write=(self)")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce
                        + "'; style-src 'nonce-" + nonce
                        + "'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; connect-src 'none'")
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(template.replace("{{nonce}}", nonce));
    }
}
