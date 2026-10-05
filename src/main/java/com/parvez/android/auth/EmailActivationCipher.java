package com.parvez.android.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** AES-GCM protection for short-lived queued tokens, bound to their receipt metadata. */
@Component
public class EmailActivationCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public EmailActivationCipher(@Value("${app.email-activation.email.encryption-key:}") String value) {
        if (value.isBlank()) { key = null; return; }
        try {
            key = Base64.getDecoder().decode(value);
            if (key.length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Email activation email encryption key must be 32 random bytes, base64 encoded");
        }
    }

    public boolean isConfigured() { return key != null; }

    public String encrypt(String token, UUID id, String email, String hash, Instant expiresAt) {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, id, email, hash, expiresAt)
                    .doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception failure) { throw new IllegalStateException("Cannot protect queued email activation email"); }
    }

    public String decrypt(String encrypted, UUID id, String email, String hash, Instant expiresAt) {
        try {
            String[] parts = encrypted.split("\\.", 2);
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, id, email, hash, expiresAt)
                    .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new IllegalStateException("Cannot read queued email activation email"); }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID id, String email, String hash, Instant expiresAt) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("email-activation:" + id + ":" + email + ":" + hash + ":" + expiresAt.toEpochMilli()).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
