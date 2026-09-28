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
