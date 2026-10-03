package com.parvez.android.library;

import com.parvez.android.saas.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {"books.storage.directory=${java.io.tmpdir}/booker-request-tests", "books.requests.email-poll-millis=3600000"})
class PublicLibraryRequestIntegrationTest {
    @Autowired WorkspaceAccounts accounts;
    @Autowired PublicLibraryRequestService requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired com.parvez.android.document.BookDocumentService documents;
    @Autowired com.parvez.android.storage.FileStorageService storage;
    WorkspacePrincipal owner, other, admin;
    MockMvc mvc;
    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString();
        accounts.register(new WorkspaceAccounts.Signup("Requests", "request-"+suffix+"@example.com", "test-password-123"));
        accounts.register(new WorkspaceAccounts.Signup("Other", "other-"+suffix+"@example.com", "test-password-123"));
        accounts.provisionSuperAdmin("review-"+suffix+"@example.com", "test-password-123");
        owner = (WorkspacePrincipal) accounts.loadUserByUsername("request-"+suffix+"@example.com");
        other = (WorkspacePrincipal) accounts.loadUserByUsername("other-"+suffix+"@example.com");
        admin = (WorkspacePrincipal) accounts.loadUserByUsername("review-"+suffix+"@example.com");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void authenticate(WorkspacePrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, principal.getPassword(), principal.getAuthorities()));
    }
    PublicLibraryBookRequest submit() {
        authenticate(owner);
        return requests.submit(new PublicLibraryRequestService.Submit("Requested "+UUID.randomUUID(), "Author"));
    }
    @Test void submissionValidationIdentityAndIsolation() throws Exception {
        mvc.perform(post("/api/public-book-requests").with(anonymous()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        for (String body : new String[]{"{}", "{\"title\":\" \",\"authorName\":\"Author\"}", "{\"title\":\"Book\",\"authorName\":\"\"}"})
            mvc.perform(post("/api/public-book-requests").with(user(owner)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/public-book-requests").with(user(owner)).contentType("application/json")
                .content("{\"title\":\"Identity "+UUID.randomUUID()+"\",\"authorName\":\"Author\",\"workspaceId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requesterEmail").value(owner.getUsername()));
        var request = submit();
        authenticate(other); assertTrue(requests.own().stream().noneMatch(r -> r.id().equals(request.id())));
        authenticate(owner);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> requests.submit(new PublicLibraryRequestService.Submit(request.title(), request.authorName())));
        mvc.perform(get("/api/admin/public-book-requests").with(user(owner))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/public-book-requests").with(user(admin)).param("status","PENDING")).andExpect(status().isOk());
    }
    @Test void submissionQueuesAdminEmailsAtomicallyAndDoesNotDuplicateOnConflict() {
        var request = submit();
        var receipt = jdbc.queryForMap("SELECT * FROM public_request_emails WHERE request_id=? AND email_type='SUBMISSION' AND recipient=?",
                request.id(), admin.getUsername());
        assertEquals("New public library book request | Booker", receipt.get("subject"));
        assertTrue(receipt.get("message").toString().contains(owner.getUsername()));
        assertTrue(receipt.get("html_message").toString().contains(request.title()));
        assertTrue(receipt.get("html_message").toString().contains("Requests"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND recipient=?",
                Integer.class, request.id(), owner.getUsername()));
        assertEquals(jdbc.queryForObject("SELECT count(*) FROM workspace_users WHERE role='SUPER_ADMIN'", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND email_type='SUBMISSION'", Integer.class, request.id()));
        var before = jdbc.queryForList("SELECT * FROM public_request_emails WHERE request_id=? ORDER BY recipient", request.id());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> requests.submit(new PublicLibraryRequestService.Submit(request.title(), request.authorName())));
        assertEquals(before, jdbc.queryForList("SELECT * FROM public_request_emails WHERE request_id=? ORDER BY recipient", request.id()));
        authenticate(admin);
        requests.reject(request.id());
        assertEquals(before, jdbc.queryForList("SELECT * FROM public_request_emails WHERE request_id=? AND email_type='SUBMISSION' ORDER BY recipient", request.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND email_type='DECISION'", Integer.class, request.id()));
    }

    @Test void submissionRollbackRemovesRequestAndEveryAdminReceipt() {
        authenticate(owner);
        var id = new java.util.concurrent.atomic.AtomicReference<UUID>();
        transaction.executeWithoutResult(status -> {
            var request = requests.submit(new PublicLibraryRequestService.Submit("Rollback " + UUID.randomUUID(), "Author"));
            id.set(request.id());
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=?", Integer.class, request.id()) > 0);
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_library_book_requests WHERE id=?", Integer.class, id.get()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=?", Integer.class, id.get()));
    }

    @Test void submissionWithoutProvisionedAdminStillPersistsRequest() {
        authenticate(owner);
        transaction.executeWithoutResult(status -> {
            // Remove admin roles only inside this rolled-back, isolated test transaction.
            jdbc.update("UPDATE workspace_users SET role='OWNER', workspace_id=? WHERE role='SUPER_ADMIN'", WorkspacePrincipal.currentWorkspace());
            var request = requests.submit(new PublicLibraryRequestService.Submit("No admin " + UUID.randomUUID(), "Author"));
            assertEquals("PENDING", request.status());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public_library_book_requests WHERE id=?", Integer.class, request.id()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=?", Integer.class, request.id()));
            status.setRollbackOnly();
        });
    }

    @Test void rejectionPersistsDecisionNotificationsAndPreventsTransitions() throws Exception {
        var request = submit();
        mvc.perform(post("/api/admin/public-book-requests/{id}/reject",request.id()).with(user(owner))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/public-book-requests/{id}/reject",request.id()).with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/admin/public-book-requests/{id}/reject",request.id()).with(user(admin))).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND email_type='DECISION'",Integer.class,request.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_events WHERE workspace_id=? AND payload::jsonb->>'requestId'=?",Integer.class,request.workspaceId(),request.id().toString()));
        authenticate(admin);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> requests.accept(request.id(),new PublicLibraryRequestService.Metadata("2026",null,false),"book.pdf","application/pdf",new ByteArrayInputStream(new byte[0])));
    }

    @Test void concurrentReviewsOnlyProduceOneDecision() throws Exception {
        var request = submit();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> review = () -> {
                authenticate(admin);
                try { requests.reject(request.id()); return true; }
                catch (org.springframework.web.server.ResponseStatusException ex) {
                    assertEquals(409, ex.getStatusCode().value()); return false;
                } finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(review);
            var second = executor.submit(review);
            assertNotEquals(first.get(), second.get());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM book_events WHERE payload::jsonb->>'requestId'=?", Integer.class, request.id().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND email_type='DECISION'", Integer.class, request.id()));
    }
    @Test void outerRollbackRemovesStoredPdfAndLeavesPendingRequest() throws Exception {
        var request = submit();
        byte[] bytes;
        try(var doc=new PDDocument();var output=new ByteArrayOutputStream()) { doc.addPage(new PDPage());doc.save(output);bytes=output.toByteArray(); }
        authenticate(admin);
        var key = new java.util.concurrent.atomic.AtomicReference<String>();
        transaction.executeWithoutResult(status -> {
            try {
                var accepted = requests.accept(request.id(),new PublicLibraryRequestService.Metadata("2026",null,false),
                        "book.pdf","application/pdf",new ByteArrayInputStream(bytes));
                key.set(documents.activePublic(accepted.bookId()).storageKey());
                assertTrue(storage.exists(key.get()));
                status.setRollbackOnly();
            } catch(IOException ex) { throw new java.io.UncheckedIOException(ex); }
        });
        assertFalse(storage.exists(key.get()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND email_type='DECISION'", Integer.class, request.id()));
        authenticate(owner);
        assertEquals("PENDING",requests.own().stream().filter(r -> r.id().equals(request.id())).findFirst().orElseThrow().status());
    }
    MockMultipartFile metadata() {
        return new MockMultipartFile("metadata","","application/json","{\"publishedDate\":\"2026\",\"description\":\"Review\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void invalidPdfRollsBackBookAndRequestThenValidAcceptanceSucceeds() throws Exception {
        var request = submit();
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept",request.id()).file(metadata())
                .file(new MockMultipartFile("file","bad.pdf","application/pdf","invalid".getBytes())).with(user(admin)))
                .andExpect(status().isUnsupportedMediaType());
        authenticate(owner); assertEquals("PENDING",requests.own().stream().filter(r->r.id().equals(request.id())).findFirst().orElseThrow().status());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM books WHERE library_type='PUBLIC' AND title=?",Integer.class,request.title()));
        byte[] bytes;
        try(var doc=new PDDocument();var output=new ByteArrayOutputStream()) { doc.addPage(new PDPage());doc.save(output);bytes=output.toByteArray(); }
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept",request.id()).file(metadata())
                .file(new MockMultipartFile("file","book.pdf","application/pdf",bytes)).with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.bookId").isNumber());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM books b JOIN book_documents d ON d.book_id=b.id WHERE b.library_type='PUBLIC' AND b.title=?",Integer.class,request.title()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=? AND email_type='DECISION'",Integer.class,request.id()));
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept",request.id()).file(metadata())
                .file(new MockMultipartFile("file","book.pdf","application/pdf",bytes)).with(user(admin))).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/public-book-requests/{id}/reject",request.id()).with(user(admin))).andExpect(status().isConflict());
    }
}
