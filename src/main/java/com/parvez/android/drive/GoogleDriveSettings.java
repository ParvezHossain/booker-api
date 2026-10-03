package com.parvez.android.drive;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("books.google-drive")
public record GoogleDriveSettings(boolean enabled, String clientId, String clientSecret, String redirectUri,
                                  String encryptionKey, String pickerApiKey, String projectNumber) {
    public GoogleDriveSettings {
        if (enabled && (blank(clientId) || blank(clientSecret) || blank(redirectUri) || blank(encryptionKey)))
            throw new IllegalArgumentException("Google Drive requires OAuth client ID, secret, redirect URI, and encryption key");
        if (enabled) {
            var uri = java.net.URI.create(redirectUri);
            boolean localhost = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || (localhost && "http".equals(uri.getScheme()))))
                throw new IllegalArgumentException("Google redirect URI must use HTTPS (HTTP is allowed only on localhost)");
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
