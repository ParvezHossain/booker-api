package com.parvez.android.library;

import com.parvez.android.dto.BookRequest;
import com.parvez.android.saas.*;
import com.parvez.android.service.BookService;
import com.parvez.android.storage.DocumentFileCleanup;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=128B", "spring.servlet.multipart.max-request-size=256B",
        "books.documents.max-size=128B", "books.documents.workspace-limit=128B", "books.documents.max-pages=1",
        "books.storage.directory=${java.io.tmpdir}/booker-public-http-tests", "books.storage.cleanup-poll-millis=3600000"})
class PublicUploadHttpTest {
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Autowired WorkspaceAccounts accounts;
    @Autowired PublicBookService publicBooks;
    @Autowired BookService privateBooks;
    @Autowired DocumentFileCleanup cleanup;

    @Test void actualServerStreamsPublicPdfBeyondMultipartCapsAndStillRejectsPrivateOversize() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String adminEmail = "http-admin-" + suffix + "@example.com", ownerEmail = "http-owner-" + suffix + "@example.com";
        String password = "http-test-password-123";
        accounts.provisionSuperAdmin(adminEmail, password);
        com.parvez.android.TestAccounts.registerVerified(accounts, jdbc, new WorkspaceAccounts.Signup("HTTP", ownerEmail, password));
        var admin = accounts.loadUserByUsername(adminEmail);
        var owner = accounts.loadUserByUsername(ownerEmail);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        long publicId = publicBooks.create(new BookRequest("HTTP public " + suffix, "Author", "2026", null, false)).id();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities()));
        long privateId = privateBooks.createBook(new BookRequest("HTTP private " + suffix, "Author", "2026", null, false)).id();
        byte[] pdf;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.addPage(new PDPage()); doc.save(output); pdf = output.toByteArray();
        }
        assertTrue(pdf.length > 256);
        try (var client = HttpClient.newHttpClient()) {
            var publicRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public-books/" + publicId + "/document?fileName=book.pdf"))
                    .header("Authorization", basic(adminEmail, password)).header("Content-Type", "application/pdf")
                    .header("Idempotency-Key", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build();
            assertEquals(201, client.send(publicRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
            var read = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public-books/" + publicId + "/document/content"))
                    .header("Authorization", basic(ownerEmail, password)).GET().build();
            var result = client.send(read, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, result.statusCode()); assertArrayEquals(pdf, result.body());
            String boundary = "booker-boundary";
            var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"book.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.write(pdf); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            var privateRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/books/" + privateId + "/document"))
                    .header("Authorization", basic(ownerEmail, password)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            assertEquals(413, client.send(privateRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
            publicBooks.delete(publicId); cleanup.processPending(); SecurityContextHolder.clearContext();
        }
    }
    private String basic(String email, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
