package com.parvez.android.drive;

import com.parvez.android.document.BookDocumentService;
import com.parvez.android.saas.*;
import com.parvez.android.storage.FileStorageService;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "books.google-drive.enabled=true", "books.google-drive.client-id=test-client", "books.google-drive.client-secret=test-secret",
        "books.google-drive.redirect-uri=http://localhost/api/integrations/google-drive/callback",
        "books.google-drive.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "books.google-drive.initial-delay=3600000", "books.storage.directory=${java.io.tmpdir}/booker-drive-tests"})
class GoogleDriveIntegrationTest {
    @MockitoBean GoogleDriveGateway gateway;
    @Autowired GoogleDriveConnectionService connections;
    @Autowired GoogleDriveImportService imports;
    @Autowired WorkspaceAccounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookDocumentService documents;
    @Autowired FileStorageService storage;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    WorkspacePrincipal user;
    long book;
    @BeforeEach void setup() {
        String email = "drive-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Drive", email, "test-password-123"));
        user = (WorkspacePrincipal) accounts.loadUserByUsername(email); authenticate();
        book = jdbc.queryForObject("INSERT INTO books (workspace_id, title, author, publication_date) VALUES (?, 'Drive', 'Author', '2026') RETURNING id",
                Long.class, WorkspacePrincipal.currentWorkspace());
        when(gateway.exchange(anyString(), anyString())).thenReturn(new GoogleDriveGateway.Tokens("access", "private-refresh", GoogleDriveGateway.SCOPE));
        when(gateway.refresh("private-refresh")).thenReturn(new GoogleDriveGateway.Tokens("access", null, GoogleDriveGateway.SCOPE));
    }
    @AfterEach void cleanup() throws Exception {
        for (String key : jdbc.queryForList("SELECT storage_key FROM book_documents WHERE book_id = ?", String.class, book)) storage.delete(key);
        jdbc.update("DELETE FROM google_drive_imports WHERE book_id = ?", book);
        SecurityContextHolder.clearContext();
    }
    void authenticate() { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities())); }
    String state(GoogleDriveConnectionService.Connect connect) { return UriComponentsBuilder.fromUriString(connect.url()).build().getQueryParams().getFirst("state"); }
    void connect() { var start = connections.begin(); connections.complete(state(start), start.binding(), "code"); }
    @Test void oauthBoundToBrowserSingleUseAndEncrypted() {
        var start = connections.begin();
        assertThrows(ResponseStatusException.class, () -> connections.complete(state(start), "wrong-browser", "code"));
        connections.complete(state(start), start.binding(), "code");
        assertTrue(connections.connected());
        assertThrows(ResponseStatusException.class, () -> connections.complete(state(start), start.binding(), "code"));
        String encrypted = jdbc.queryForObject("SELECT refresh_token_encrypted FROM google_drive_connections WHERE user_email = ?", String.class, user.getUsername());
        assertFalse(encrypted.contains("private-refresh"));
        assertEquals("access", connections.accessToken());
        connections.disconnect(); assertFalse(connections.connected());
    }
    @Test void expiredAndRevokedAuthorization() {
        var start = connections.begin();
        jdbc.update("UPDATE google_drive_oauth_states SET expires_at = now() - interval '1 second' WHERE user_email = ?", user.getUsername());
        assertThrows(ResponseStatusException.class, () -> connections.complete(state(start), start.binding(), "code"));
        connect();
        when(gateway.refresh("private-refresh")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Reconnect Drive"));
        assertThrows(ResponseStatusException.class, connections::accessToken);
        assertFalse(connections.connected());
    }
    @Test void importsPdfAndSurvivesDisconnect() throws Exception {
        connect();
        byte[] pdf;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) { doc.addPage(new PDPage()); doc.save(output); pdf = output.toByteArray(); }
        when(gateway.metadata("access", "selected-file")).thenReturn(new GoogleDriveGateway.DriveFile("selected.pdf", "application/pdf", pdf.length, true));
        when(gateway.download(eq("access"), eq("selected-file"), any())).thenAnswer(invocation -> {
            GoogleDriveGateway.Download<?> consumer = invocation.getArgument(2);
            return consumer.read(new ByteArrayInputStream(pdf));
        });
        var id = UUID.randomUUID(); imports.start(book, id, "selected-file");
        imports.processNext(); authenticate();
        assertEquals("COMPLETED", imports.status(book, id).status());
        assertEquals("GOOGLE_DRIVE", documents.active(book).sourceType());
        imports.start(book, id, "selected-file");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        connections.disconnect();
        assertEquals(1, documents.active(book).pageCount());
        assertEquals("COMPLETED", imports.start(book, id, "selected-file").status());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> imports.start(book, id, "another-file")).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> imports.start(book, UUID.randomUUID(), "selected-file")).getStatusCode().value());
    }
    @Test void unsupportedFilesAndRateLimits() {
        connect();
        when(gateway.metadata("access", "not-pdf")).thenReturn(new GoogleDriveGateway.DriveFile("image.png", "image/png", 100, true));
        var id = UUID.randomUUID(); imports.start(book, id, "not-pdf"); imports.processNext(); authenticate();
        assertEquals("FAILED", imports.status(book, id).status());
        when(gateway.metadata("access", "busy")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google busy"));
        var retry = UUID.randomUUID(); imports.start(book, retry, "busy"); imports.processNext(); authenticate();
        assertEquals("PENDING", imports.status(book, retry).status());
        verify(gateway, never()).download(anyString(), anyString(), any());
    }
    @Test void transientImportsStopAfterThreeAttempts() {
        connect();
        when(gateway.metadata("access", "busy")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google busy"));
        UUID id = UUID.randomUUID(); imports.start(book, id, "busy");
        for (int attempt = 1; attempt <= 3; attempt++) {
            jdbc.update("UPDATE google_drive_imports SET available_at = now() - interval '1 second' WHERE id = ?", id);
            imports.processNext(); authenticate();
            assertEquals(attempt < 3 ? "PENDING" : "FAILED", imports.status(book, id).status());
            assertEquals(attempt, jdbc.queryForObject("SELECT attempts FROM google_drive_imports WHERE id = ?", Integer.class, id));
        }
        imports.processNext(); authenticate();
        verify(gateway, times(3)).metadata("access", "busy");
        verify(gateway, never()).download(anyString(), anyString(), any());
    }
    @Test void deniedDownloadsFailAndDisconnectCancelsPendingImports() {
        connect();
        when(gateway.metadata("access", "denied")).thenReturn(new GoogleDriveGateway.DriveFile("book.pdf", "application/pdf", 100, false));
        UUID denied = UUID.randomUUID(); imports.start(book, denied, "denied");
        imports.processNext(); authenticate();
        assertEquals("FAILED", imports.status(book, denied).status());
        UUID pending = UUID.randomUUID(); imports.start(book, pending, "pending");
        connections.disconnect();
        assertEquals("FAILED", imports.status(book, pending).status());
        verify(gateway, never()).download(anyString(), anyString(), any());
    }
    @Test void importApiRejectsUrlsAndEnforcesWorkspaceAndOwner() throws Exception {
        connect();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String url = "/api/books/" + book + "/document/imports/google-drive";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(user))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                        .content("{\"fileId\":\"https://drive.google.com/file/d/untrusted\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        authenticate(); UUID id = UUID.randomUUID(); imports.start(book, id, "selected-file");
        String email = "other-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Other workspace", email, "test-password-123"));
        var other = accounts.loadUserByUsername(email);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/books/" + book + "/document/imports/" + id)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(other)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        authenticate();
        jdbc.update("UPDATE workspace_users SET workspace_id = ? WHERE email = ?", WorkspacePrincipal.currentWorkspace(), email);
        var teammate = accounts.loadUserByUsername(email);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/books/" + book + "/document/imports/" + id)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(teammate)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
}
