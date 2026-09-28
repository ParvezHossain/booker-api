package com.parvez.android.drive;

import com.parvez.android.saas.WorkspacePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class GoogleDriveConnectionService {
    private final GoogleDriveSettings settings;
    private final DriveCredentialCipher cipher;
    private final GoogleDriveGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public GoogleDriveConnectionService(GoogleDriveSettings settings, DriveCredentialCipher cipher,
                                       GoogleDriveGateway gateway, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.settings = settings; this.cipher = cipher; this.gateway = gateway; this.jdbc = jdbc; this.transaction = transaction;
    }
    public Connect begin() {
        enabled();
        String email = WorkspacePrincipal.currentEmail();
        String state = random(), binding = random(), verifier = random();
        jdbc.update("DELETE FROM google_drive_oauth_states WHERE expires_at <= now() OR user_email = ?", email);
        jdbc.update("INSERT INTO google_drive_oauth_states (state_hash, binding_hash, user_email, verifier_encrypted, expires_at) VALUES (?, ?, ?, ?, now() + interval '10 minutes')",
                hash(state), hash(binding), email, cipher.encrypt(verifier, email));
        String url = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", settings.clientId()).queryParam("redirect_uri", settings.redirectUri())
                .queryParam("response_type", "code").queryParam("scope", GoogleDriveGateway.SCOPE)
                .queryParam("access_type", "offline").queryParam("prompt", "consent")
                .queryParam("state", state).queryParam("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier)))
                .queryParam("code_challenge_method", "S256").build().encode().toUriString();
        return new Connect(url, binding);
    }
    public void complete(String state, String binding, String code) {
        enabled();
        if (state == null || binding == null || code == null || state.length() > 200 || code.length() > 4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Google authorization response");
        var rows = transaction.execute(status -> jdbc.queryForList("DELETE FROM google_drive_oauth_states WHERE state_hash = ? AND binding_hash = ? AND expires_at > now() RETURNING user_email, verifier_encrypted", hash(state), hash(binding)));
        if (rows == null || rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google authorization expired or belongs to another browser; connect again");
        String email = (String) rows.getFirst().get("user_email");
        var tokens = gateway.exchange(code, cipher.decrypt((String) rows.getFirst().get("verifier_encrypted"), email));
        if (tokens.refreshToken() == null || !Arrays.asList(tokens.scope().split(" ")).contains(GoogleDriveGateway.SCOPE))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grant selected-file access and reconnect Google Drive");
        jdbc.update("INSERT INTO google_drive_connections (user_email, refresh_token_encrypted) VALUES (?, ?) ON CONFLICT (user_email) DO UPDATE SET refresh_token_encrypted = EXCLUDED.refresh_token_encrypted, connected_at = now()",
                email, cipher.encrypt(tokens.refreshToken(), email));
    }
    public boolean connected() {
        return settings.enabled() && jdbc.queryForObject("SELECT count(*) FROM google_drive_connections WHERE user_email = ?", Integer.class, WorkspacePrincipal.currentEmail()) > 0;
    }
    public String accessToken() {
        enabled();
        String email = WorkspacePrincipal.currentEmail();
        String encrypted = jdbc.query("SELECT refresh_token_encrypted FROM google_drive_connections WHERE user_email = ?",
                (rs, row) -> rs.getString(1), email).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Connect Google Drive first"));
        try {
            var tokens = gateway.refresh(cipher.decrypt(encrypted, email));
            return tokens.accessToken();
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 403) jdbc.update("DELETE FROM google_drive_connections WHERE user_email = ? AND refresh_token_encrypted = ?", email, encrypted);
            throw ex;
        }
    }
    public void disconnect() {
        String email = WorkspacePrincipal.currentEmail();
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM google_drive_connections WHERE user_email = ?", email);
            jdbc.update("DELETE FROM google_drive_oauth_states WHERE user_email = ?", email);
            jdbc.update("UPDATE google_drive_imports SET status = 'FAILED', message = 'Google Drive disconnected', updated_at = now() WHERE user_email = ? AND status = 'PENDING'", email);
        });
    }
    public Map<String, String> picker() {
        if (settings.pickerApiKey() == null || settings.pickerApiKey().isBlank() || settings.projectNumber() == null || settings.projectNumber().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Picker is not configured");
        return Map.of("accessToken", accessToken(), "apiKey", settings.pickerApiKey(), "appId", settings.projectNumber());
    }
    private void enabled() { if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Drive is not configured"); }
    private String random() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    public record Connect(String url, String binding) {}
}
