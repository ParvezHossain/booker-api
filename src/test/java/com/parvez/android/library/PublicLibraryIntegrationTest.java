package com.parvez.android.library;

import com.parvez.android.auth.TokenService;
import com.parvez.android.document.*;
import com.parvez.android.dto.BookRequest;
import com.parvez.android.reading.ReadingProgressService;
import com.parvez.android.saas.*;
import com.parvez.android.service.BookService;
import com.parvez.android.storage.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {"books.documents.max-size=128B", "books.documents.workspace-limit=128B", "books.documents.max-pages=1",
        "books.storage.directory=${java.io.tmpdir}/booker-public-library-tests", "books.storage.cleanup-poll-millis=3600000"})
class PublicLibraryIntegrationTest {
    @Autowired WorkspaceAccounts accounts;
    @Autowired PublicBookService publicBooks;
    @Autowired BookService privateBooks;
    @Autowired BookDocumentService documents;
    @Autowired ReadingProgressService progress;
    @Autowired FileStorageService storage;
    @Autowired DocumentFileCleanup cleanup;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    WorkspacePrincipal admin, ownerA, ownerB, teammate;
    long book;
    byte[] pdf;
    MockMvc mvc;
    final String password = "test-password-123";

    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString();
        accounts.provisionSuperAdmin("admin-" + suffix + "@example.com", password);
        admin = (WorkspacePrincipal) accounts.loadUserByUsername("admin-" + suffix + "@example.com");
        ownerA = signup("a-" + suffix + "@example.com");
        ownerB = signup("b-" + suffix + "@example.com");
        authenticate(ownerA);
        String teammateEmail = "teammate-" + suffix + "@example.com";
        jdbc.update("INSERT INTO workspace_users (email, password_hash, workspace_id) VALUES (?, ?, ?)",
                teammateEmail, ownerA.getPassword(), WorkspacePrincipal.currentWorkspace());
        teammate = (WorkspacePrincipal) accounts.loadUserByUsername(teammateEmail);
        authenticate(admin);
        book = publicBooks.create(request("Public " + suffix)).id();
        pdf = pdf(4);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void teardown() throws Exception {
        authenticate(admin);
        // Test-owned books only; other test workspaces/accounts are intentionally retained.
        if (jdbc.queryForObject("SELECT count(*) FROM books WHERE id = ?", Integer.class, book) > 0) publicBooks.delete(book);
        cleanup.processPending();
        SecurityContextHolder.clearContext();
    }
    private WorkspacePrincipal signup(String email) {
        accounts.register(new WorkspaceAccounts.Signup("Public reader", email, password));
        return (WorkspacePrincipal) accounts.loadUserByUsername(email);
    }
    private void authenticate(WorkspacePrincipal user) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }
    private BookRequest request(String title) { return new BookRequest(title, "Author", "2026", "Description", false); }
    private String body(String title) { return "{\"title\":\"" + title + "\",\"author\":\"Author\",\"publishedDate\":\"2026\",\"completed\":false}"; }
    private BookDocument upload(UUID operation, byte[] bytes) throws Exception {
        authenticate(admin);
        return documents.uploadPublic(book, operation, "../public.pdf", new ByteArrayInputStream(bytes));
    }
    private byte[] pdf(int pages) throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(output); return output.toByteArray();
        }
    }
    private String saveBody(UUID document, int page, long version, UUID operation) {
        return "{\"documentId\":\"" + document + "\",\"currentPage\":" + page + ",\"version\":" + version + ",\"operationId\":\"" + operation + "\"}";
    }

    @Test void superAdminCanManageBooksUsingExistingJwtAndBasicAuthentication() throws Exception {
        String token = tokens.login(admin.getUsername(), password).accessToken();
        mvc.perform(post("/api/public-books").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(body("JWT " + UUID.randomUUID())))
                .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/public-books/")));
        mvc.perform(put("/api/public-books/{id}", book).with(httpBasic(admin.getUsername(), password)).contentType("application/json")
                .content(body("Updated " + UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.author").value("Author"));
        assertNull(jdbc.queryForMap("SELECT workspace_id FROM books WHERE id = ?", book).get("workspace_id"));
        mvc.perform(delete("/api/public-books/{id}", book).with(user(admin))).andExpect(status().isNoContent());
        mvc.perform(get("/api/public-books/{id}", book).with(user(ownerA))).andExpect(status().isNotFound());
    }
    @Test void ordinaryAccountsCannotManagePublicBooksOrPdfsAndAnonymousCannotRead() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/public-books").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public-books/{id}/document/content", book).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/public-books/{id}/document/content", book).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public-books").with(user(ownerA)).contentType("application/json").content(body("Forbidden"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/public-books/{id}", book).with(user(ownerA)).contentType("application/json").content(body("Forbidden"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/public-books/{id}", book).with(user(ownerA))).andExpect(status().isForbidden());
        mvc.perform(post("/api/public-books/{id}/document", book).with(user(ownerA)).param("fileName", "public.pdf")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/pdf").content(pdf)).andExpect(status().isForbidden());
        authenticate(ownerA);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> publicBooks.create(request("Forbidden")));
    }
    @Test void publicBooksAreGlobalWhilePrivateListsQuotasAndEventsRemainIsolated() throws Exception {
        for (var owner : new WorkspacePrincipal[]{ownerA, ownerB}) {
            mvc.perform(get("/api/public-books/{id}", book).with(user(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(book));
            mvc.perform(get("/api/books/{id}", book).with(user(owner))).andExpect(status().isNotFound());
            authenticate(owner);
            assertTrue(privateBooks.getBooks().isEmpty());
            assertEquals(0, ((Number) accounts.current().get("books_used")).intValue());
        }
        authenticate(ownerA);
        long privateId = privateBooks.createBook(request(publicBooks.get(book).title())).id();
        assertEquals(1, privateBooks.getBooks().size());
        mvc.perform(get("/api/books/{id}", privateId).with(user(ownerB))).andExpect(status().isNotFound());
        mvc.perform(get("/api/public-books/{id}", privateId).with(user(ownerA))).andExpect(status().isNotFound());
        mvc.perform(get("/api/public-books/{id}/document", privateId).with(user(ownerA))).andExpect(status().isNotFound());
        mvc.perform(get("/api/public-books/{id}/reading-progress", privateId).with(user(ownerA))).andExpect(status().isNotFound());
        mvc.perform(post("/api/public-books/{id}/document", privateId).with(user(admin)).param("fileName", "public.pdf")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/pdf").content(pdf)).andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_events WHERE (payload::jsonb->'book'->>'id')::bigint = ?", Integer.class, book));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_events WHERE (payload::jsonb->'book'->>'id')::bigint = ?", Integer.class, privateId));
    }
    @Test void publicPdfBypassesPrivateSizePageStorageAndBookQuotasButRetainsValidation() throws Exception {
        assertTrue(pdf.length > 128);
        mvc.perform(post("/api/public-books/{id}/document", book).with(user(admin)).param("fileName", "../public.pdf")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/pdf").content(pdf))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pageCount").value(4)).andExpect(jsonPath("$.fileName").value("public.pdf"));
        authenticate(admin);
        var original = documents.activePublic(book);
        mvc.perform(post("/api/public-books/{id}/document", book).with(user(admin)).param("fileName", "bad.pdf")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/pdf").content("not pdf"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/public-books/{id}/document", book).with(user(admin)).param("fileName", "bad.txt")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/pdf").content(pdf))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/public-books/{id}/document", book).with(user(admin)).param("fileName", "empty.pdf")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/pdf").content(new byte[0])).andExpect(status().isBadRequest());
        mvc.perform(post("/api/public-books/{id}/document", book).with(user(admin)).param("fileName", "bad.pdf")
                .contentType("application/pdf").content(pdf)).andExpect(status().isBadRequest());
        authenticate(admin);
        assertEquals(original.id(), documents.activePublic(book).id());
        authenticate(ownerA);
        long privateId = privateBooks.createBook(request("Private limit")).id();
        assertEquals(413, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> documents.upload(privateId, UUID.randomUUID(), "private.pdf", "application/pdf", new ByteArrayInputStream(pdf), "UPLOAD")).getStatusCode().value());
    }
    @Test void publicPdfStreamsRangesAndHeadersAcrossWorkspacesWithoutLeakingPrivateRoutes() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        for (var owner : new WorkspacePrincipal[]{ownerA, ownerB}) {
            mvc.perform(get("/api/public-books/{id}/document/content", book).with(user(owner)))
                    .andExpect(status().isOk()).andExpect(content().bytes(pdf)).andExpect(header().string("Accept-Ranges", "bytes"));
            mvc.perform(get("/api/public-books/{id}/document/content", book).with(user(owner)).header("Range", "bytes=0-9"))
                    .andExpect(status().isPartialContent()).andExpect(content().bytes(java.util.Arrays.copyOf(pdf, 10)));
            mvc.perform(head("/api/public-books/{id}/document/content", book).with(user(owner)))
                    .andExpect(status().isOk()).andExpect(header().string("Content-Length", String.valueOf(pdf.length))).andExpect(content().string(""));
            mvc.perform(get("/api/books/{id}/document/content", book).with(user(owner))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/public-books/{id}/document/content", book).with(user(ownerA)).header("Range", "bytes=999999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        upload(UUID.randomUUID(), pdf(2));
        mvc.perform(get("/api/public-books/{id}/document/content", book).with(user(ownerB)).param("documentId", doc.id().toString()))
                .andExpect(status().isConflict());
    }
    @Test void progressIsSharedWithinWorkspaceIsolatedAcrossWorkspacesAndCompletes() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        mvc.perform(get("/api/public-books/{id}/reading-progress", book).with(user(ownerA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(0)).andExpect(jsonPath("$.progressPercentage").value(0));
        UUID operation = UUID.randomUUID();
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerA)).contentType("application/json")
                .content(saveBody(doc.id(), 3, 0, operation))).andExpect(status().isOk()).andExpect(jsonPath("$.progressPercentage").value(75));
        mvc.perform(get("/api/public-books/{id}/reading-progress", book).with(user(teammate)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(3));
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(teammate)).contentType("application/json")
                .content(saveBody(doc.id(), 3, 0, operation))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/public-books/{id}/reading-progress", book).with(user(ownerB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(0));
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerB)).contentType("application/json")
                .content(saveBody(doc.id(), 1, 0, operation))).andExpect(status().isOk()).andExpect(jsonPath("$.progressPercentage").value(25));
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(teammate)).contentType("application/json")
                .content(saveBody(doc.id(), 4, 1, UUID.randomUUID()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(100)).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(get("/api/public-books/{id}/reading-progress", book).with(user(ownerB))).andExpect(jsonPath("$.currentPage").value(1));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM public_reading_progress WHERE book_id = ?", Integer.class, book));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, book));
        mvc.perform(get("/api/public-books/{id}/reading-progress", book).with(user(admin))).andExpect(status().isForbidden());
    }
    @Test void invalidPagesRevisionAndForeignWorkspaceSelectorsCannotChangeProgress() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        for (int page : new int[]{-1, 0, 5, 101}) {
            mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerA)).contentType("application/json")
                    .content(saveBody(doc.id(), page, 0, UUID.randomUUID()))).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerA)).contentType("application/json")
                .content("{\"documentId\":\"" + doc.id() + "\",\"currentPage\":1,\"operationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        authenticate(ownerB); UUID workspaceB = WorkspacePrincipal.currentWorkspace();
        // An unrecognized workspaceId never supplies ownership, even if ignored by the JSON converter.
        String attack = saveBody(doc.id(), 2, 0, UUID.randomUUID()).replace("}", ",\"workspaceId\":\"" + workspaceB + "\",\"progressPercentage\":150}");
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerA)).contentType("application/json").content(attack))
                .andExpect(status().isOk()).andExpect(jsonPath("$.progressPercentage").value(50));
        mvc.perform(get("/api/public-books/{id}/reading-progress", book).with(user(ownerB)).param("workspaceId", workspaceB.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(0));
        mvc.perform(get("/api/public-books/{id}/workspaces/{workspace}/reading-progress", book, workspaceB).with(user(ownerA)))
                .andExpect(status().isNotFound());
    }
    @Test void retryConflictsReplacementAndBackwardNavigationUseSameProgressRules() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        authenticate(ownerA);
        UUID operation = UUID.randomUUID();
        var initial = progress.updatePublic(book, new ReadingProgressService.Update(doc.id(), 2, 0, operation)).progress();
        authenticate(teammate);
        assertEquals(initial, progress.updatePublic(book, new ReadingProgressService.Update(doc.id(), 2, 0, operation)).progress());
        var conflict = progress.updatePublic(book, new ReadingProgressService.Update(doc.id(), 4, 0, UUID.randomUUID()));
        assertTrue(conflict.conflict()); assertTrue(conflict.progress().completed()); assertEquals(2, conflict.progress().resumePage());
        var backwards = progress.updatePublic(book, new ReadingProgressService.Update(doc.id(), 1, conflict.progress().version(), UUID.randomUUID())).progress();
        assertTrue(backwards.completed()); assertEquals(100, backwards.progressPercentage().intValue()); assertEquals(1, backwards.currentPage());
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerA)).contentType("application/json")
                .content(saveBody(doc.id(), 3, 0, operation))).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        upload(UUID.randomUUID(), pdf(2)); authenticate(ownerA);
        assertEquals(0, progress.getPublic(book).currentPage());
        mvc.perform(put("/api/public-books/{id}/reading-progress", book).with(user(ownerA)).contentType("application/json")
                .content(saveBody(doc.id(), 2, backwards.version(), UUID.randomUUID()))).andExpect(status().isConflict());
    }
    @Test void summariesAreWorkspaceScopedAndRejectPrivateIds() throws Exception {
        upload(UUID.randomUUID(), pdf); authenticate(ownerA);
        var doc = documents.activePublic(book);
        progress.updatePublic(book, new ReadingProgressService.Update(doc.id(), 3, 0, UUID.randomUUID()));
        mvc.perform(get("/api/public-books/reading-summaries").param("bookIds", String.valueOf(book)).with(user(teammate)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].progress.progressPercentage").value(75));
        mvc.perform(get("/api/public-books/reading-summaries").param("bookIds", String.valueOf(book)).with(user(ownerB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].progress.progressPercentage").value(0));
        authenticate(ownerA);
        long privateId = privateBooks.createBook(request("Private summary")).id();
        mvc.perform(get("/api/public-books/reading-summaries").param("bookIds", book + "," + privateId).with(user(ownerA)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/books/reading-summaries").param("bookIds", String.valueOf(book)).with(user(ownerA))).andExpect(status().isNotFound());
    }
    @Test void deletionCascadesProgressAndDurablyQueuesAllRetainedFiles() throws Exception {
        UUID operation = UUID.randomUUID();
        var first = upload(operation, pdf);
        assertEquals(first.id(), upload(operation, pdf).id());
        var second = upload(UUID.randomUUID(), pdf(2));
        authenticate(ownerA);
        progress.updatePublic(book, new ReadingProgressService.Update(second.id(), 2, 0, UUID.randomUUID()));
        authenticate(admin); publicBooks.delete(book);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_documents WHERE book_id = ?", Integer.class, book));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_reading_progress WHERE book_id = ?", Integer.class, book));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_reading_progress_operations WHERE book_id = ?", Integer.class, book));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM document_file_deletions WHERE storage_key IN (?, ?)", Integer.class, first.storageKey(), second.storageKey()));
        cleanup.processPending();
        assertFalse(storage.exists(first.storageKey())); assertFalse(storage.exists(second.storageKey()));
        cleanup.processPending();
        mvc.perform(head("/api/public-books/{id}/document/content", book).with(user(ownerB))).andExpect(status().isNotFound());
    }
    @Test void concurrentWorkspaceUpdatesCannotCreateDuplicateRecords() throws Exception {
        var doc = upload(UUID.randomUUID(), pdf);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<ReadingProgressService.UpdateResult> save = () -> {
                authenticate(ownerA);
                try { return progress.updatePublic(book, new ReadingProgressService.Update(doc.id(), 2, 0, UUID.randomUUID())); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(save); var second = executor.submit(save);
            assertNotEquals(first.get().conflict(), second.get().conflict());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public_reading_progress WHERE book_id = ?", Integer.class, book));
    }
    @Test void duplicateGlobalPairsConflictAndSwaggerDescribesPublicApi() throws Exception {
        mvc.perform(post("/api/public-books").with(user(admin)).contentType("application/json").content(body(publicBooks.get(book).title())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("A book with this author and title already exists in the public library"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/public-books'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/public-books/{bookId}/document'].post.requestBody.content['application/pdf']").exists())
                .andExpect(jsonPath("$.paths['/api/public-books/{bookId}/reading-progress'].put.responses['409']").exists());
    }
}
