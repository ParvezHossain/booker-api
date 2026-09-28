package com.parvez.android.drive;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
public class GoogleDriveGateway {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private final GoogleDriveSettings settings;
    private final RestClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public GoogleDriveGateway(GoogleDriveSettings settings) {
        this.settings = settings;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        client = RestClient.builder().requestFactory(factory).build();
    }
    GoogleDriveGateway(GoogleDriveSettings settings, RestClient client) { this.settings = settings; this.client = client; }
    public Tokens exchange(String code, String verifier) {
        return token(Map.of("code", code, "code_verifier", verifier, "redirect_uri", settings.redirectUri(), "grant_type", "authorization_code"));
    }
    public Tokens refresh(String refreshToken) { return token(Map.of("refresh_token", refreshToken, "grant_type", "refresh_token")); }
    private Tokens token(Map<String, String> values) {
        var form = new LinkedMultiValueMap<String, String>();
        values.forEach(form::add); form.add("client_id", settings.clientId()); form.add("client_secret", settings.clientSecret());
        try {
            JsonNode json = client.post().uri("https://oauth2.googleapis.com/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().onStatus(status -> status.value() >= 400, (request, response) -> { throw failure(response.getStatusCode().value()); })
                    .body(JsonNode.class);
            if (json == null || !json.hasNonNull("access_token")) throw failure(502);
            return new Tokens(json.path("access_token").asText(), json.path("refresh_token").asText(null), json.path("scope").asText(""));
        } catch (RestClientException ex) { throw failure(503); }
    }
    public DriveFile metadata(String accessToken, String fileId) {
        try {
            var json = client.get().uri("https://www.googleapis.com/drive/v3/files/{id}?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true", fileId)
                    .headers(headers -> headers.setBearerAuth(accessToken)).retrieve()
                    .onStatus(status -> status.value() >= 400, (request, response) -> { throw failure(response.getStatusCode().value()); })
                    .body(JsonNode.class);
            if (json == null) throw failure(502);
            return new DriveFile(json.path("name").asText(), json.path("mimeType").asText(), json.path("size").asLong(-1),
                    json.path("capabilities").path("canDownload").asBoolean(false));
        } catch (RestClientException ex) { throw failure(503); }
    }
    public <T> T download(String token, String fileId, Download<T> consumer) {
        try {
            return client.get().uri("https://www.googleapis.com/drive/v3/files/{id}?alt=media&supportsAllDrives=true", fileId)
                    .headers(headers -> headers.setBearerAuth(token)).exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw failure(response.getStatusCode().value());
                        return consumer.read(response.getBody());
                    });
        } catch (RestClientException ex) { throw failure(503); }
    }
    private ResponseStatusException failure(int status) {
        if (status == 429 || status >= 500) return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Drive is temporarily unavailable; retry later");
        if (status == 404) return new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected Drive file is unavailable");
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Google Drive access was denied; reconnect and select the file again");
    }
    public record Tokens(String accessToken, String refreshToken, String scope) {}
    public record DriveFile(String name, String mimeType, long size, boolean canDownload) {}
    @FunctionalInterface public interface Download<T> { T read(InputStream stream) throws IOException; }
}
