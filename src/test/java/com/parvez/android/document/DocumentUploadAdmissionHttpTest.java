package com.parvez.android.document;

import com.parvez.android.TestAccounts;
import com.parvez.android.auth.TokenService;
import com.parvez.android.dto.BookRequest;
import com.parvez.android.library.PublicBookService;
import com.parvez.android.saas.WorkspaceAccounts;
import com.parvez.android.storage.DocumentFileCleanup;
import com.parvez.android.storage.LocalFileStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "books.documents.max-concurrent-uploads=1", "books.documents.max-concurrent-parsers=1",
        "books.storage.directory=${java.io.tmpdir}/booker-admission-http-tests",
        "books.storage.cleanup-poll-millis=3600000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentUploadAdmissionHttpTest {
    @LocalServerPort int port;
    @Autowired WorkspaceAccounts accounts;
    @Autowired PublicBookService books;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentFileCleanup cleanup;
    @MockitoSpyBean LocalFileStorageService storage;

    @Test void rejectsBodiesBeforeConsumptionPreservesAuthorizationAndServesOneHundredReadersDuringUpload() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String adminEmail = "admission-admin-" + suffix + "@example.com";
        String ownerEmail = "admission-owner-" + suffix + "@example.com";
        String password = "admission-test-password-123";
        accounts.provisionSuperAdmin(adminEmail, password);
        TestAccounts.registerVerified(accounts, jdbc, new WorkspaceAccounts.Signup("Admission", ownerEmail, password));
        var admin = accounts.loadUserByUsername(adminEmail);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        long bookId;
        try { bookId = books.create(new BookRequest("Admission " + suffix, "Author", "2026", null, false)).id(); }
        finally { SecurityContextHolder.clearContext(); }
        String adminToken = tokens.login(adminEmail, password).accessToken();
        String ownerToken = tokens.login(ownerEmail, password).accessToken();
        byte[] pdf;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(output); pdf = output.toByteArray();
        }
        String uploadPath = "/api/public-books/" + bookId + "/document?fileName=book.pdf";
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(201, client.send(upload(uploadPath, adminToken, HttpRequest.BodyPublishers.ofByteArray(pdf)),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            doAnswer(call -> {
                entered.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                return call.callRealMethod();
            }).when(storage).upload(any(), anyLong());
            var active = client.sendAsync(upload(uploadPath, adminToken, HttpRequest.BodyPublishers.ofByteArray(pdf)),
                    HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (String path : new String[]{uploadPath, uploadPath.replace("/document", "/%64ocument"), "/api/books/1/document",
                        "/api/admin/public-book-requests/" + UUID.randomUUID() + "/accept"}) {
                    var consumed = new AtomicBoolean();
                    var request = upload(path, adminToken, HttpRequest.BodyPublishers.ofInputStream(() -> {
                        consumed.set(true); return new ByteArrayInputStream(pdf);
                    }));
                    var rejected = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, rejected.statusCode(), rejected.body());
                    assertEquals("5", rejected.headers().firstValue("Retry-After").orElseThrow());
                    assertTrue(rejected.body().contains("PDF upload capacity is busy; retry later"));
                    assertFalse(consumed.get(), "Expect: 100-continue body must remain unread at overload");
                }
                var denied = client.send(upload(uploadPath, ownerToken, HttpRequest.BodyPublishers.ofByteArray(pdf)),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, denied.statusCode());
                var anonymous = HttpRequest.newBuilder(uri(uploadPath)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build();
                assertEquals(401, client.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode());
                var read = HttpRequest.newBuilder(uri("/api/public-books/" + bookId + "/document/content"))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + ownerToken)
                        .header("Range", "bytes=0-127").GET().build();
                var readers = java.util.stream.IntStream.range(0, 100)
                        .mapToObj(i -> client.sendAsync(read, HttpResponse.BodyHandlers.ofByteArray())).toList();
                for (var reader : readers) {
                    var result = reader.get(10, TimeUnit.SECONDS);
                    assertEquals(206, result.statusCode());
                    assertArrayEquals(Arrays.copyOf(pdf, 128), result.body());
                }
            } finally { release.countDown(); }
            assertEquals(201, active.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(201, client.send(upload(uploadPath, adminToken, HttpRequest.BodyPublishers.ofByteArray(pdf)),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "Completed upload must release its slot");
        } finally {
            release.countDown();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
            try { books.delete(bookId); cleanup.processPending(); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }

    private HttpRequest upload(String path, String token, HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).expectContinue(true)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/pdf")
                .header("Idempotency-Key", UUID.randomUUID().toString()).POST(body).build();
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
