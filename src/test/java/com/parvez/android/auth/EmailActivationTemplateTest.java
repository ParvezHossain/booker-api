package com.parvez.android.auth;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EmailActivationTemplateTest {
    final String token = "A".repeat(43);
    final Instant expiry = Instant.parse("2026-10-05T12:00:00Z");

    @Test void templateMatchesBrandEscapesContentAndProvidesEquivalentCopyableToken() throws Exception {
        var mail = new EmailActivationTemplate("Asia/Dhaka", "").render("owner@example.com", "<script>{{token}}& Library", token, expiry);
        assertTrue(mail.html().contains("Booker")); assertTrue(mail.html().contains("Workspace activation"));
        assertTrue(mail.html().contains("&lt;script&gt;{{token}}&amp; Library"));
        assertFalse(mail.html().contains("<script>")); assertTrue(mail.html().contains(token));
        assertTrue(mail.text().contains(token)); assertTrue(mail.text().contains("05 Oct 2026, 06:00:00 PM (Asia/Dhaka +06:00)"));
        assertFalse(mail.html().contains("href=")); assertTrue(mail.text().contains("Only activate a workspace you created"));
    }
    @Test void appLinkUsesFragmentAndExplicitConfirmationWithTrustedHttpsOrigin() throws Exception {
        var mail = new EmailActivationTemplate("UTC", "https://booker.example/activate").render("owner@example.com", "Workspace", token, expiry);
        assertTrue(mail.text().contains("https://booker.example/activate#token=" + token + "&expiresAt=" + expiry.toEpochMilli()));
        assertTrue(mail.html().contains("Open activation screen")); assertTrue(mail.html().contains("&amp;expiresAt="));
        for (String url : new String[]{"http://example.com/activate", "https://user:pass@example.com/activate", "https://example.com/a?token=x", "https://example.com/a#x", "javascript:alert(1)"})
            assertThrows(IllegalArgumentException.class, () -> new EmailActivationTemplate("UTC", url));
    }
    @Test void encryptedReceiptIsBoundToItsPurposeAndMetadataAndConfigurationIsValidated() {
        var cipher = new EmailActivationCipher("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=");
        UUID id = UUID.randomUUID(); String email = "owner@example.com", hash = com.parvez.android.security.OpaqueTokens.sha256Hex(token);
        String encrypted = cipher.encrypt(token, id, email, hash, expiry);
        assertEquals(token, cipher.decrypt(encrypted, id, email, hash, expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, UUID.randomUUID(), email, hash, expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, id, "other@example.com", hash, expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, id, email, hash, expiry.plusSeconds(1)));
        var recovery = new PasswordResetEmailCipher("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=");
        assertThrows(IllegalStateException.class, () -> recovery.decrypt(encrypted, id, email, hash, expiry));
        assertThrows(IllegalArgumentException.class, () -> new EmailActivationCipher("invalid"));
        assertFalse(new EmailActivationCipher("").isConfigured());
    }
}
