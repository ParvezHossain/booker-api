package com.parvez.android.document;

import com.parvez.android.saas.*;
import com.parvez.android.reading.*;
import com.parvez.android.storage.FileStorageService;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"books.documents.max-size=2MB", "books.storage.directory=${java.io.tmpdir}/booker-document-tests"})
class BookReadingIntegrationTest {
    @Autowired WorkspaceAccounts accounts;
    @Autowired BookDocumentService documents;
    @Autowired ReadingProgressService progress;
    @Autowired com.parvez.android.service.BookService books;
    @Autowired FileStorageService storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    WorkspacePrincipal owner;
    WorkspacePrincipal other;
    WorkspacePrincipal teammate;
    long book;
    MockMvc mvc;
    byte[] pdf;
    @BeforeEach void setup() throws Exception {
        owner = signup(); other = signup();
        String teammateEmail = "reader-" + UUID.randomUUID() + "@example.com";
        authenticate(owner);
        jdbc.update("INSERT INTO workspace_users (email, password_hash, workspace_id) VALUES (?, ?, ?)",
                teammateEmail, owner.getPassword(), WorkspacePrincipal.currentWorkspace());
        teammate = (WorkspacePrincipal) accounts.loadUserByUsername(teammateEmail);
        book = jdbc.queryForObject("INSERT INTO books (workspace_id, title, author, publication_date, completed) VALUES (?, 'PDF', 'Author', '2026', false) RETURNING id",
                Long.class, WorkspacePrincipal.currentWorkspace());
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        pdf = pdf(144);
    }
    @AfterEach void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        for (String key : jdbc.queryForList("SELECT storage_key FROM book_documents WHERE book_id = ?", String.class, book)) storage.delete(key);
    }
    private WorkspacePrincipal signup() {
        String email = "pdf-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Reading test", email, "test-password-123"));
        return (WorkspacePrincipal) accounts.loadUserByUsername(email);
    }
    private void authenticate(WorkspacePrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
    private BookDocument upload(UUID operation, byte[] bytes) throws Exception {
        return documents.upload(book, operation, "book.pdf", "application/pdf", new ByteArrayInputStream(bytes), "UPLOAD");
    }
    private byte[] pdf(int pages) throws Exception {
        try (var doc = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(bytes); return bytes.toByteArray();
        }
    }
    @Test void uploadMetadataStreamingRangeAndRetry() throws Exception {
        UUID operation = UUID.randomUUID();
        mvc.perform(multipart("/api/books/{id}/document", book).file(new MockMultipartFile("file", "../book.pdf", "application/pdf", pdf))
                .header("Idempotency-Key", operation).with(user(owner)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fileName").value("book.pdf"))
                .andExpect(jsonPath("$.pageCount").value(144)).andExpect(jsonPath("$.storageKey").doesNotExist());
        authenticate(owner);
        var first = upload(operation, pdf);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        mvc.perform(get("/api/books/{id}/document", book).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value(first.id().toString()));
        mvc.perform(get("/api/books/{id}/document/content", book).with(user(owner)))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf)).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/api/books/{id}/document/content", book).header("Range", "bytes=0-4").with(user(owner)))
                .andExpect(status().isPartialContent()).andExpect(content().bytes("%PDF-".getBytes()))
                .andExpect(header().string("Content-Range", "bytes 0-4/" + pdf.length));
        mvc.perform(get("/api/books/{id}/document/content", book).header("Range", "bytes=99999999-").with(user(owner)))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(head("/api/books/{id}/document/content", book).with(user(owner)))
                .andExpect(status().isOk()).andExpect(header().longValue("Content-Length", pdf.length));
    }
    @Test void contentSupportsSuffixAndOpenEndedRanges() throws Exception {
        upload(UUID.randomUUID(), pdf);
        int start = pdf.length - 10;
        for (String range : List.of("bytes=-10", "bytes=" + start + "-")) {
            mvc.perform(get("/api/books/{id}/document/content", book).header("Range", range).with(user(owner)))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string("Accept-Ranges", "bytes"))
                    .andExpect(header().string("Content-Range", "bytes " + start + "-" + (pdf.length - 1) + "/" + pdf.length))
                    .andExpect(content().bytes(Arrays.copyOfRange(pdf, start, pdf.length)));
        }
        mvc.perform(get("/api/books/{id}/document/content", book).header("Range", "bytes=invalid").with(user(owner)))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }
    @Test void contentAndHeadHavePrivateDownloadHeaders() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        for (boolean download : List.of(false, true)) {
            var response = mvc.perform(get("/api/books/{id}/document/content", book)
                            .param("download", Boolean.toString(download)).with(user(owner)))
                    .andExpect(status().isOk()).andExpect(content().bytes(pdf))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("ETag", '"' + doc.id().toString() + '"')).andReturn().getResponse();
            var disposition = org.springframework.http.ContentDisposition.parse(response.getHeader("Content-Disposition"));
            assertEquals(download ? "attachment" : "inline", disposition.getType());
            assertEquals("book.pdf", disposition.getFilename());
            mvc.perform(head("/api/books/{id}/document/content", book).header("Range", "bytes=0-4")
                            .param("download", Boolean.toString(download)).with(user(owner)))
                    .andExpect(status().isOk()).andExpect(content().bytes(new byte[0]))
                    .andExpect(header().longValue("Content-Length", pdf.length))
                    .andExpect(header().string("Content-Type", "application/pdf"))
                    .andExpect(header().string("Accept-Ranges", "bytes"))
                    .andExpect(header().string("Content-Disposition", response.getHeader("Content-Disposition")))
                    .andExpect(header().doesNotExist("Content-Range"));
        }
    }
    @Test void streamingAndHeadAuthorizeBeforeVersionOrRangeChecks() throws Exception {
        var original = upload(UUID.randomUUID(), pdf);
        upload(UUID.randomUUID(), pdf(10));
        for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.HEAD)) {
            String url = "/api/books/" + book + "/document/content";
            mvc.perform(request(method, url).param("documentId", original.id().toString()).with(user(owner)))
                    .andExpect(status().isConflict());
            mvc.perform(request(method, url).param("documentId", original.id().toString())
                            .header("Range", "bytes=0-4").with(user(other)))
                    .andExpect(status().isNotFound());
            mvc.perform(request(method, url).header("Range", "bytes=0-4")
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test void streamingAndHeadHandleMissingPdfAndUnavailableStorage() throws Exception {
        String url = "/api/books/" + book + "/document/content";
        for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.HEAD)) {
            mvc.perform(request(method, url).with(user(owner))).andExpect(status().isNotFound());
        }
        authenticate(owner);
        var doc = upload(UUID.randomUUID(), pdf);
        storage.delete(doc.storageKey());
        for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.HEAD)) {
            mvc.perform(request(method, url).with(user(owner))).andExpect(status().isServiceUnavailable());
        }
        mvc.perform(get(url).with(user(owner)))
                .andExpect(jsonPath("$.message").value("Document content is unavailable"));
    }
    @Test void uploadHttpRejectsMissingFieldsAndInvalidPdfs() throws Exception {
        var valid = new MockMultipartFile("file", "book.pdf", "application/pdf", pdf);
        mvc.perform(multipart("/api/books/{id}/document", book).file(valid).with(user(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/books/{id}/document", book).file(valid).header("Idempotency-Key", "invalid").with(user(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/books/{id}/document", book).header("Idempotency-Key", UUID.randomUUID()).with(user(owner)))
                .andExpect(status().isBadRequest());
        for (var invalid : List.of(
                new MockMultipartFile("file", "book.txt", "application/pdf", pdf),
                new MockMultipartFile("file", "book.pdf", "image/png", pdf),
                new MockMultipartFile("file", "book.pdf", "application/pdf", "not a PDF".getBytes()))) {
            mvc.perform(multipart("/api/books/{id}/document", book).file(invalid)
                            .header("Idempotency-Key", UUID.randomUUID()).with(user(owner)))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
        }
        mvc.perform(multipart("/api/books/{id}/document", book)
                        .file(new MockMultipartFile("file", "book.pdf", "application/pdf", new byte[0]))
                        .header("Idempotency-Key", UUID.randomUUID()).with(user(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/books/{id}/document", book)
                        .file(new MockMultipartFile("file", "book.pdf", "application/pdf", new byte[2 * 1024 * 1024 + 1]))
                        .header("Idempotency-Key", UUID.randomUUID()).with(user(owner)))
                .andExpect(status().is(413));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
    }
    @Test void uploadHttpRequiresAuthenticationAndAnExistingBook() throws Exception {
        var file = new MockMultipartFile("file", "book.pdf", "application/pdf", pdf);
        mvc.perform(multipart("/api/books/{id}/document", book).file(file).header("Idempotency-Key", UUID.randomUUID())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/books/{id}/document", Long.MAX_VALUE).file(file)
                        .header("Idempotency-Key", UUID.randomUUID()).with(user(owner)))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
    }
    @Test void uploadHttpReturnsMetadataAndPreservesBookOnReplacement() throws Exception {
        var original = upload(UUID.randomUUID(), pdf);
        var before = jdbc.queryForMap("SELECT * FROM books WHERE id = ?", book);
        byte[] replacement = pdf(12);
        mvc.perform(multipart("/api/books/{id}/document", book)
                        .file(new MockMultipartFile("file", "C:\\books\\new\nbook.PDF", "application/pdf", replacement))
                        .header("Idempotency-Key", UUID.randomUUID()).with(user(owner)))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/books/" + book + "/document"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.bookId").value(book)).andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.fileName").value("new_book.PDF"))
                .andExpect(jsonPath("$.fileSize").value(replacement.length))
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.pageCount").value(12)).andExpect(jsonPath("$.sourceType").value("UPLOAD"))
                .andExpect(jsonPath("$.storageKey").doesNotExist()).andExpect(jsonPath("$.storageProvider").doesNotExist());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM books WHERE id = ?", book));
        assertFalse(jdbc.queryForObject("SELECT active FROM book_documents WHERE id = ?", Boolean.class, original.id()));
        assertTrue(storage.exists(original.storageKey()));
    }
    @Test void authorizationAndPerUserProgress() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        progress.update(book, new ReadingProgressService.Update(doc.id(), 93, 0, UUID.randomUUID()));
        authenticate(teammate);
        assertEquals(0, progress.get(book).currentPage());
        for (String suffix : List.of("document", "document/content", "reading-progress")) {
            mvc.perform(get("/api/books/" + book + "/" + suffix).with(user(other))).andExpect(status().isNotFound());
        }
        mvc.perform(multipart("/api/books/{id}/document", book).file(new MockMultipartFile("file", "book.pdf", "application/pdf", pdf))
                .header("Idempotency-Key", UUID.randomUUID()).with(user(other))).andExpect(status().isNotFound());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/books/{id}/document/content", book).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()).header("Range", "bytes=0-4")).andExpect(status().isUnauthorized());
    }
    @Test void rejectsInvalidOversizedAndEncryptedFiles() throws Exception {
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> upload(UUID.randomUUID(), "not a pdf".getBytes())).getStatusCode().value());
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> upload(UUID.randomUUID(), new byte[2 * 1024 * 1024 + 1])).getStatusCode().value());
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> documents.upload(book, UUID.randomUUID(), "bad.exe", "application/pdf", new ByteArrayInputStream(pdf), "UPLOAD")).getStatusCode().value());
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> documents.upload(book, UUID.randomUUID(), "book.pdf", "image/png", new ByteArrayInputStream(pdf), "UPLOAD")).getStatusCode().value());
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "reader", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(output);
            assertEquals(415, assertThrows(ResponseStatusException.class, () -> upload(UUID.randomUUID(), output.toByteArray())).getStatusCode().value());
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        assertThrows(IllegalArgumentException.class, () -> storage.download("../../etc/passwd"));
    }
    @Test void progressIdempotencyCompletionReplacementAndBounds() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        assertEquals(1, progress.get(book).resumePage());
        var request = new ReadingProgressService.Update(doc.id(), 93, 0, UUID.randomUUID());
        var read = progress.update(book, request).progress();
        assertEquals("64.58", read.progressPercentage().toPlainString());
        assertEquals(93, read.resumePage());
        assertEquals(read, progress.update(book, request).progress());
        var finished = progress.update(book, new ReadingProgressService.Update(doc.id(), 144, read.version(), UUID.randomUUID())).progress();
        assertTrue(finished.completed());
        assertFalse(jdbc.queryForObject("SELECT completed FROM books WHERE id = ?", Boolean.class, book));
        var back = progress.update(book, new ReadingProgressService.Update(doc.id(), 20, finished.version(), UUID.randomUUID())).progress();
        assertEquals(20, back.resumePage()); assertEquals(144, back.pagesRead());
        assertThrows(ResponseStatusException.class, () -> progress.update(book, new ReadingProgressService.Update(doc.id(), 145, back.version(), UUID.randomUUID())));
        var replacement = upload(UUID.randomUUID(), pdf(10));
        assertNotEquals(doc.id(), replacement.id()); assertEquals(0, progress.get(book).currentPage());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> progress.update(book, request)).getStatusCode().value());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
    }
    @Test void personalCompletionIsPerUserAndResetsOnDocumentReplacement() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        var completed = progress.update(book, new ReadingProgressService.Update(doc.id(), 144, 0, UUID.randomUUID())).progress();
        assertTrue(completed.completed());
        var backwards = progress.update(book, new ReadingProgressService.Update(doc.id(), 20, completed.version(), UUID.randomUUID())).progress();
        assertTrue(backwards.completed());
        mvc.perform(get("/api/books/{id}/reading-progress", book).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(get("/api/books/reading-summaries").param("bookIds", Long.toString(book)).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].progress.completed").value(true));
        authenticate(teammate);
        assertFalse(progress.get(book).completed());
        assertFalse(progress.update(book, new ReadingProgressService.Update(doc.id(), 10, 0, UUID.randomUUID())).progress().completed());
        authenticate(owner);
        var replacement = upload(UUID.randomUUID(), pdf(10));
        assertFalse(progress.get(book).completed());
        assertEquals(0, progress.get(book).pagesRead());
        assertFalse(progress.update(book, new ReadingProgressService.Update(replacement.id(), 1, 0, UUID.randomUUID())).progress().completed());
        assertFalse(jdbc.queryForObject("SELECT completed FROM books WHERE id = ?", Boolean.class, book));
    }
    @Test void manuallyCompletedBookRemainsCompletedWhilePersonalReadingChanges() throws Exception {
        var created = books.createBook(new com.parvez.android.dto.BookRequest("Already read",
                "Author", "2026", "Manual completion", true));
        book = created.id();
        assertTrue(created.completed());
        var doc = upload(UUID.randomUUID(), pdf);
        assertFalse(progress.get(book).completed());
        var started = progress.update(book, new ReadingProgressService.Update(doc.id(), 1, 0, UUID.randomUUID())).progress();
        assertFalse(started.completed());
        assertTrue(books.getBookById(created.id()).completed());
        assertTrue(progress.update(book, new ReadingProgressService.Update(doc.id(), 144, started.version(), UUID.randomUUID())).progress().completed());
        upload(UUID.randomUUID(), pdf(10));
        assertFalse(progress.get(book).completed());
        mvc.perform(get("/api/books/{bookId}", created.id()).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
    }
    @Test void staleFinalPageMergeCompletesWithoutMovingResumePosition() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        var saved = progress.update(book, new ReadingProgressService.Update(doc.id(), 80, 0, UUID.randomUUID())).progress();
        var result = progress.update(book, new ReadingProgressService.Update(doc.id(), 144, 0, UUID.randomUUID()));
        assertTrue(result.conflict());
        assertTrue(result.progress().completed());
        assertEquals(80, result.progress().resumePage());
        assertEquals(saved.lastReadAt(), result.progress().lastReadAt());
        assertFalse(jdbc.queryForObject("SELECT completed FROM books WHERE id = ?", Boolean.class, book));
    }
    @Test void progressResumesAndRetriesDoNotChangePersistedState() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        var initial = progress.get(book);
        assertEquals(0, initial.currentPage());
        assertEquals(1, initial.resumePage());
        assertNull(initial.lastReadAt());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));

        var request = new ReadingProgressService.Update(doc.id(), 93, 0, UUID.randomUUID());
        var saved = progress.update(book, request).progress();
        assertEquals(144, saved.totalPages());
        assertEquals(93, saved.pagesRead());
        assertEquals("64.58", saved.progressPercentage().toPlainString());
        assertNotNull(saved.lastReadAt());
        var row = jdbc.queryForMap("SELECT * FROM reading_progress WHERE book_id = ?", book);
        assertEquals(saved, progress.update(book, request).progress());
        assertEquals(row, jdbc.queryForMap("SELECT * FROM reading_progress WHERE book_id = ?", book));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/books/{id}/reading-progress", book).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(93))
                .andExpect(jsonPath("$.resumePage").value(93));
    }
    @Test void progressIsUniquePerUserAndCannotBeRedirectedToAnotherAccount() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        UUID operation = UUID.randomUUID();
        progress.update(book, new ReadingProgressService.Update(doc.id(), 93, 0, operation));
        authenticate(teammate);
        // Retry identities are scoped to the account, not shared across the workspace.
        progress.update(book, new ReadingProgressService.Update(doc.id(), 20, 0, operation));
        assertEquals(20, progress.get(book).resumePage());
        authenticate(owner);
        assertEquals(93, progress.get(book).resumePage());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO reading_progress (user_email, book_id, document_id, current_page, max_page_reached, version, last_read_at)
                VALUES (?, ?, ?, 1, 1, 1, now())
                """, owner.getUsername(), book, doc.id()));
        authenticate(other);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> progress.update(book, new ReadingProgressService.Update(doc.id(), 144, 1, UUID.randomUUID())))
                .getStatusCode().value());
        authenticate(owner);
        assertEquals(93, progress.get(book).resumePage());
    }
    @Test void reusedProgressOperationCannotChangeItsPageOrRevision() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        UUID operation = UUID.randomUUID();
        var saved = progress.update(book, new ReadingProgressService.Update(doc.id(), 93, 0, operation)).progress();
        for (var request : List.of(new ReadingProgressService.Update(doc.id(), 94, 0, operation),
                new ReadingProgressService.Update(doc.id(), 93, saved.version(), operation))) {
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> progress.update(book, request)).getStatusCode().value());
        }
        assertEquals(saved, progress.get(book));
    }
    @Test void futureRevisionCannotChangeProgressButStaleRevisionMergesMaximum() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        var saved = progress.update(book, new ReadingProgressService.Update(doc.id(), 80, 0, UUID.randomUUID())).progress();
        var future = progress.update(book, new ReadingProgressService.Update(doc.id(), 144, saved.version() + 1, UUID.randomUUID()));
        assertTrue(future.conflict());
        assertEquals(saved, future.progress());
        assertEquals(saved, progress.get(book));
        var stale = new ReadingProgressService.Update(doc.id(), 93, 0, UUID.randomUUID());
        var merged = progress.update(book, stale);
        assertTrue(merged.conflict());
        assertEquals(80, merged.progress().resumePage());
        assertEquals(93, merged.progress().pagesRead());
        assertEquals(saved.lastReadAt(), merged.progress().lastReadAt());
        assertEquals(merged, progress.update(book, stale));
    }
    @Test void concurrentUpdatesPreserveOneResumeAndMergeMaximum() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(updateTask(doc, 80), updateTask(doc, 93)));
            assertNotEquals(results.get(0).get().conflict(), results.get(1).get().conflict());
        }
        assertEquals(93, progress.get(book).pagesRead());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
    }
    @Test void concurrentUploadRetriesCreateOneDocument() throws Exception {
        UUID operation = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(uploadTask(operation), uploadTask(operation)));
            assertEquals(results.get(0).get().id(), results.get(1).get().id());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        assertTrue(storage.exists(documents.active(book).storageKey()));
    }
    @Test void concurrentReplacementsRetainVersionsWithOneActiveDocument() throws Exception {
        var original = upload(UUID.randomUUID(), pdf);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(uploadTask(UUID.randomUUID()), uploadTask(UUID.randomUUID())));
            var first = results.get(0).get();
            var second = results.get(1).get();
            assertNotEquals(first.id(), second.id());
            assertTrue(Set.of(first.id(), second.id()).contains(documents.active(book).id()));
            for (var document : List.of(original, first, second)) assertTrue(storage.exists(document.storageKey()));
        }
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ? AND active", Integer.class, book));
    }
    @Test void metadataFailureRollsBackDocumentReplacement() throws Exception {
        var original = upload(UUID.randomUUID(), pdf);
        // Fail the INSERT after activation has first deactivated the existing row.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> documents.upload(book, UUID.randomUUID(), "book.pdf", "application/pdf",
                        new ByteArrayInputStream(pdf), "INVALID_SOURCE"));
        assertEquals(original.id(), documents.active(book).id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        try (var input = storage.download(original.storageKey()).getInputStream()) {
            assertArrayEquals(pdf, input.readAllBytes());
        }
    }
    @Test void progressHttpFirstOpenSaveAndRetry() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        String url = "/api/books/" + book + "/reading-progress";
        mvc.perform(get(url).with(user(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.currentPage").value(0)).andExpect(jsonPath("$.resumePage").value(1))
                .andExpect(jsonPath("$.totalPages").value(144)).andExpect(jsonPath("$.pagesRead").value(0))
                .andExpect(jsonPath("$.progressPercentage").value(0)).andExpect(jsonPath("$.lastReadAt").isEmpty())
                .andExpect(jsonPath("$.version").value(0));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
        String body = progressBody(doc.id(), 93, 0, UUID.randomUUID());
        String response = mvc.perform(put(url).with(user(owner)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.currentPage").value(93)).andExpect(jsonPath("$.pagesRead").value(93))
                .andExpect(jsonPath("$.progressPercentage").value(64.58)).andExpect(jsonPath("$.lastReadAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(1)).andReturn().getResponse().getContentAsString();
        mvc.perform(put(url).with(user(owner)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(content().json(response));
        mvc.perform(get(url).with(user(owner))).andExpect(status().isOk()).andExpect(content().json(response));
        mvc.perform(get(url).with(user(teammate))).andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(0));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
    }
    @Test void progressHttpRejectsMissingNullAndInvalidFields() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        String body = progressBody(doc.id(), 93, 0, UUID.randomUUID());
        for (String invalid : List.of(
                body.replace("\"version\":0,", ""), body.replace("\"version\":0", "\"version\":null"),
                body.replace("\"version\":0", "\"version\":-1"),
                body.replace("\"currentPage\":93", "\"currentPage\":0"),
                body.replace("\"currentPage\":93", "\"currentPage\":145"),
                body.replace("\"currentPage\":93,", ""),
                body.replace("\"currentPage\":93", "\"currentPage\":null"),
                body.replace("\"" + doc.id() + "\"", "null"),
                body.replace("\"" + doc.id() + "\"", "\"invalid-uuid\""),
                body.replaceFirst("\"operationId\":\"[^\"]+\"", "\"operationId\":null"))) {
            mvc.perform(put("/api/books/{id}/reading-progress", book).with(user(owner))
                            .contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
    }
    @Test void progressHttpConflictsPreserveResumeAndRejectReusedOperations() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        String url = "/api/books/" + book + "/reading-progress";
        UUID operation = UUID.randomUUID();
        mvc.perform(put(url).with(user(owner)).contentType("application/json").content(progressBody(doc.id(), 80, 0, operation)))
                .andExpect(status().isOk());
        mvc.perform(put(url).with(user(owner)).contentType("application/json").content(progressBody(doc.id(), 93, 0, operation)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(put(url).with(user(owner)).contentType("application/json").content(progressBody(doc.id(), 144, 2, UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.currentPage").value(80))
                .andExpect(jsonPath("$.pagesRead").value(80)).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(url).with(user(owner)).contentType("application/json").content(progressBody(doc.id(), 93, 0, UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.currentPage").value(80))
                .andExpect(jsonPath("$.pagesRead").value(93)).andExpect(jsonPath("$.version").value(2));
    }
    @Test void progressHttpRequiresAuthenticationAndAnAccessibleDocument() throws Exception {
        String url = "/api/books/" + book + "/reading-progress";
        String body = progressBody(UUID.randomUUID(), 1, 0, UUID.randomUUID());
        for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.PUT)) {
            mvc.perform(request(method, url).contentType("application/json").content(body)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(request(method, url).contentType("application/json").content(body).with(user(owner)))
                    .andExpect(status().isNotFound());
        }
        authenticate(owner);
        var doc = upload(UUID.randomUUID(), pdf);
        for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.PUT)) {
            mvc.perform(request(method, url).contentType("application/json")
                            .content(progressBody(doc.id(), 93, 0, UUID.randomUUID())).with(user(other)))
                    .andExpect(status().isNotFound());
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
    }
    private String progressBody(UUID document, int page, long version, UUID operation) {
        return "{\"documentId\":\"" + document + "\",\"currentPage\":" + page + ",\"version\":" + version
                + ",\"operationId\":\"" + operation + "\"}";
    }
    @Test void httpProgressValidationSummariesAndCors() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        String update = "{\"documentId\":\"" + doc.id() + "\",\"currentPage\":93,\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(put("/api/books/{id}/reading-progress", book).with(user(owner)).contentType("application/json").content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.progressPercentage").value(64.58));
        mvc.perform(put("/api/books/{id}/reading-progress", book).with(user(other)).contentType("application/json").content(update))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/books/{id}/reading-progress", book).with(user(owner)).contentType("application/json").content(update.replace(":93", ":0")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/books/reading-summaries").param("bookIds", Long.toString(book)).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].progress.currentPage").value(93));
        mvc.perform(get("/api/books/reading-summaries").param("bookIds", Long.toString(book)).with(user(other)))
                .andExpect(status().isNotFound());
        mvc.perform(options("/api/books/{id}/reading-progress", book).header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "PUT").header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk());
    }
    @Test void rejectsNestedPdfJavascriptAndPreservesActivePdfOnFailure() throws Exception {
        var original = upload(UUID.randomUUID(), pdf);
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            var link = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
            link.setAction(new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('script')"));
            page.getAnnotations().add(link); doc.save(output);
            assertThrows(ResponseStatusException.class, () -> upload(UUID.randomUUID(), output.toByteArray()));
        }
        assertEquals(original.id(), documents.active(book).id());
        jdbc.update("UPDATE book_documents SET file_size = 6442450944 WHERE id = ?", original.id());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> upload(UUID.randomUUID(), pdf)).getStatusCode().value());
        assertEquals(original.id(), documents.active(book).id());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
    }
    @Test void swaggerDocumentsNewEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/books/{bookId}/document'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/books/{bookId}/reading-progress'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/integrations/google-drive/callback'].get.security").doesNotExist());
    }
    private Callable<ReadingProgressService.UpdateResult> updateTask(BookDocument doc, int page) {
        return () -> { authenticate(owner); try { return progress.update(book, new ReadingProgressService.Update(doc.id(), page, 0, UUID.randomUUID())); }
            finally { SecurityContextHolder.clearContext(); } };
    }
    private Callable<BookDocument> uploadTask(UUID operation) {
        return () -> { authenticate(owner); try { return upload(operation, pdf); }
            finally { SecurityContextHolder.clearContext(); } };
    }
}
