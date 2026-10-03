package com.parvez.android.drive;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class DriveCredentialCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public DriveCredentialCipher(GoogleDriveSettings settings) {
        key = settings.enabled() ? Base64.getDecoder().decode(settings.encryptionKey()) : null;
        if (key != null && key.length != 32) throw new IllegalArgumentException("Drive encryption key must be 32 random bytes, base64 encoded");
    }
    public String encrypt(String value, String account) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            var cipher = cipher(Cipher.ENCRYPT_MODE, iv, account);
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Cannot protect Google credentials"); }
    }
    public String decrypt(String value, String account) {
        try {
            String[] parts = value.split("\\.", 2);
            var cipher = cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]), account);
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Cannot read Google credentials"); }
    }
    private Cipher cipher(int mode, byte[] iv, String account) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        // Bind ciphertext to its account so copied credential rows cannot be decrypted by another user.
        cipher.updateAAD(account.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
