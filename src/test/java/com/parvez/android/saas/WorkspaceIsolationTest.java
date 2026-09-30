package com.parvez.android.saas;

import com.parvez.android.dto.BookRequest;
import com.parvez.android.service.BookService;
import com.parvez.android.notification.BookEventStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WorkspaceIsolationTest {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired WorkspaceAccounts accounts;
    @Autowired BookService books;
    @Autowired BookEventStore events;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private UUID signup(String email) {
        var result = accounts.register(new WorkspaceAccounts.Signup("Test workspace", email, "test-password-123"));
        return (UUID) result.get("workspaceId");
    }
    private void login(String email) {
        var user = accounts.loadUserByUsername(email);
        assertTrue(passwords.matches("test-password-123", user.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
    @Test void httpAuthenticationAndSignupValidation() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/books"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/signup")
                .contentType("application/json").content("{\"workspaceName\":\"Test\",\"email\":\"bad\",\"password\":\"short\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        String email = "http-" + UUID.randomUUID() + "@example.com";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/signup")
                .contentType("application/json").content("{\"workspaceName\":\"Test\",\"email\":\"" + email + "\",\"password\":\"test-password-123\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workspace")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(email, "test-password-123")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/books")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(email, "wrong")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test void booksSearchEventsAndQuotasAreIsolated() {
        String suffix = UUID.randomUUID().toString();
        String first = "first-" + suffix + "@example.com", second = "second-" + suffix + "@example.com";
        UUID a = signup(first), b = signup(second);
        var request = new BookRequest("Title", "Author", "2018", "Private", false);
        login(first);
        var firstBook = books.createBook(request);
        assertEquals(1, books.getBooks().size());
        login(second);
        assertTrue(books.getBooks().isEmpty());
        assertTrue(books.getBooksByAuthor("Author").isEmpty());
        assertTrue(books.getBooksByTitle("Title").isEmpty());
        assertTrue(books.getBooksByAuthorAndTitle("Author", "Title").isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> books.getBookById(firstBook.id())).getStatusCode().value());
        assertTrue(events.after(0, b).isEmpty());
        books.createBook(request); // Same author/title pair is allowed in another workspace.
        assertEquals(1, events.after(0, a).size());
        assertEquals(1, events.after(0, b).size());
        assertThrows(com.parvez.android.exception.BookAlreadyExistsException.class, () -> books.createBook(request));
        jdbc.update("UPDATE workspaces SET book_limit = 1 WHERE id = ?", b);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> books.createBook(
                new BookRequest("Another", "Author", "2026", null, false))).getStatusCode().value());
        assertEquals(1, books.getBooks().size());
        long before = jdbc.queryForObject("SELECT count(*) FROM workspaces", Long.class);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> signup(second.toUpperCase()));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM workspaces", Long.class));
    }
    @Test void authorTitlePairIsUniqueButIndividualFieldsCanBeReused() {
        String email = "identity-" + UUID.randomUUID() + "@example.com";
        signup(email); login(email);
        var original = new BookRequest("Title", "Author", "2026", null, false);
        books.createBook(original);
        assertThrows(com.parvez.android.exception.BookAlreadyExistsException.class, () -> books.createBook(original));
        books.createBook(new BookRequest("Another title", "Author", "2026", null, false));
        books.createBook(new BookRequest("Title", "Another author", "2026", null, false));
        books.createBook(new BookRequest("title", "Author", "2026", null, false));
        assertEquals(4, books.getBooks().size());
    }

    @Test void concurrentDuplicateCreatesCommitOnlyOneBookAndNotification() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.com";
        UUID workspace = signup(email);
        var user = accounts.loadUserByUsername(email);
        var request = new BookRequest("Concurrent title", "Author", "2026", null, false);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
                try {
                    gate.await();
                    books.createBook(request);
                    return true;
                } catch (com.parvez.android.exception.BookAlreadyExistsException expected) {
                    return false;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            var first = executor.submit(create); var second = executor.submit(create);
            gate.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM books WHERE workspace_id = ?", Integer.class, workspace));
        assertEquals(1, events.after(0, workspace).size());
    }

    @Test void numericLookupCreationAndSwaggerExposeOnlyCurrentBookFields() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String email = "contract-" + UUID.randomUUID() + "@example.com";
        signup(email);
        var credentials = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(email, "test-password-123");
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/books")
                .with(credentials).contentType("application/json")
                .content("{\"title\":\"Title\",\"author\":\"Author\",\"publishedDate\":\"2026\",\"completed\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.isbn").doesNotExist())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.matches("http://localhost/api/books/[0-9]+"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location).with(credentials))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Title"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.isbn").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/books/not-a-number").with(credentials))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/books")
                .with(credentials).contentType("application/json")
                .content("{\"title\":\"Title\",\"author\":\"Author\",\"publishedDate\":\"2026\",\"completed\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.paths['/api/books/{bookId}'].get").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.paths['/api/books/isbn/{isbn}']").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.components.schemas.BookRequest.properties.isbn").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.components.schemas.BookResponse.properties.isbn").doesNotExist());
    }

}
