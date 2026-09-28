package com.parvez.android.drive;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GoogleDriveGatewayTest {
    @Test void drive403RateLimitsAreRetryableForMetadataAndDownloads() {
        for (String reason : java.util.List.of("rateLimitExceeded", "userRateLimitExceeded")) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var gateway = new GoogleDriveGateway(new GoogleDriveSettings(false, "client", "secret", "", "", "", ""), builder.build());
            String body = "{\"error\":{\"message\":\"private upstream detail\",\"errors\":[{\"reason\":\"" + reason + "\"}]}}";
            server.expect(requestTo("https://www.googleapis.com/drive/v3/files/selected?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN).body(body).contentType(MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://www.googleapis.com/drive/v3/files/selected?alt=media&supportsAllDrives=true"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN).body(body).contentType(MediaType.APPLICATION_JSON));
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> gateway.metadata("access", "selected")).getStatusCode().value());
            var failure = assertThrows(ResponseStatusException.class, () -> gateway.download("access", "selected", input -> {
                fail("Error bodies must never be imported as PDFs"); return null;
            }));
            assertEquals(503, failure.getStatusCode().value());
            assertFalse(failure.getMessage().contains("private upstream detail"));
            server.verify();
        }
    }
    @Test void deniedMalformedAndOversizedErrorsStayDenied() {
        for (String body : java.util.List.of("{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"}]}}",
                "not json", "x".repeat(20000))) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var gateway = new GoogleDriveGateway(new GoogleDriveSettings(false, "client", "secret", "", "", "", ""), builder.build());
            server.expect(requestTo("https://www.googleapis.com/drive/v3/files/selected?alt=media&supportsAllDrives=true"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN).body(body));
            assertEquals(403, assertThrows(ResponseStatusException.class,
                    () -> gateway.download("access", "selected", java.io.InputStream::readAllBytes)).getStatusCode().value());
            server.verify();
        }
    }
    @Test void readsGoogleTokenAndStreamsPdfResponse() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var settings = new GoogleDriveSettings(false, "client", "secret", "http://localhost/callback", "", "", "");
        var gateway = new GoogleDriveGateway(settings, builder.build());
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"scope\":\"https://www.googleapis.com/auth/drive.file\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/drive/v3/files/selected?alt=media&supportsAllDrives=true"))
                .andExpect(header("Authorization", "Bearer access")).andRespond(withSuccess("%PDF-stream", MediaType.APPLICATION_PDF));
        assertEquals("access", gateway.exchange("code", "verifier").accessToken());
        assertArrayEquals("%PDF-stream".getBytes(), gateway.download("access", "selected", java.io.InputStream::readAllBytes));
        server.verify();
    }
    @Test void revokedCredentialsAndRateLimitsHaveSafeErrors() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleDriveGateway(new GoogleDriveSettings(false, "client", "secret", "", "", "", ""), builder.build());
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}"));
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.refresh("revoked-secret")).getStatusCode().value());
        var error = assertThrows(ResponseStatusException.class, () -> gateway.refresh("secret"));
        assertEquals(503, error.getStatusCode().value()); assertFalse(error.getMessage().contains("secret"));
        server.verify();
    }
}
