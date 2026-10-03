package com.parvez.android.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random bearer secrets and their digests; this is not a password hashing utility. */
public final class OpaqueTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {}

    /** 256 bits of entropy, encoded without padding for URLs and OAuth PKCE. */
    public static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM", ex);
        }
    }

    /** Store a digest rather than a reusable refresh/reset secret in the database. */
    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }
}
