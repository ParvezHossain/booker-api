package com.parvez.android.auth;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PasswordResetEmailCipherTest {
    static final String TEST_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Test void authenticatedEncryptionBindsReceiptAccountDigestAndExpiry() {
        var cipher = new PasswordResetEmailCipher(TEST_KEY);
        UUID id = UUID.randomUUID();
        String token = "A".repeat(43), email = "reader@example.com", hash = "b".repeat(64);
        Instant expiry = Instant.parse("2026-10-04T10:30:00Z");
        String encrypted = cipher.encrypt(token, id, email, hash, expiry);
        assertFalse(encrypted.contains(token));
        assertEquals(token, cipher.decrypt(encrypted, id, email, hash, expiry));
        assertNotEquals(encrypted, cipher.encrypt(token, id, email, hash, expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, UUID.randomUUID(), email, hash, expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, id, "other@example.com", hash, expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, id, email, "c".repeat(64), expiry));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted, id, email, hash, expiry.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("invalid", id, email, hash, expiry));
    }

    @Test void blankKeyDisablesRecoveryAndMalformedKeysFailStartupWithoutLeakingInput() {
        assertFalse(new PasswordResetEmailCipher("").isConfigured());
        for (String key : new String[]{"not base64!", "QQ=="}) {
            var error = assertThrows(IllegalArgumentException.class, () -> new PasswordResetEmailCipher(key));
            assertFalse(error.getMessage().contains(key));
        }
    }
}
