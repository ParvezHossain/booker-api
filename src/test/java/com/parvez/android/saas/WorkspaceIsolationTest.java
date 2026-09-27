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
        var request = new BookRequest("9780134685991", "Title", "Author", "2018", "Private", false);
        login(first);
        books.createBook(request);
        assertEquals(1, books.getBooks().size());
        login(second);
        assertTrue(books.getBooks().isEmpty());
        assertTrue(books.getBooksByAuthor("Author").isEmpty());
        assertTrue(books.getBooksByTitle("Title").isEmpty());
        assertTrue(books.getBooksByAuthorAndTitle("Author", "Title").isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> books.getBookByISBN(request.isbn())).getStatusCode().value());
        assertTrue(events.after(0, b).isEmpty());
        books.createBook(request); // Same ISBN is allowed in another workspace.
        assertEquals(1, events.after(0, a).size());
        assertEquals(1, events.after(0, b).size());
        assertThrows(com.parvez.android.exception.BookAlreadyExistsException.class, () -> books.createBook(request));
        jdbc.update("UPDATE workspaces SET book_limit = 1 WHERE id = ?", b);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> books.createBook(
                new BookRequest("1234567890", "Another", "Author", "2026", null, false))).getStatusCode().value());
        assertEquals(1, books.getBooks().size());
        long before = jdbc.queryForObject("SELECT count(*) FROM workspaces", Long.class);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> signup(second.toUpperCase()));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM workspaces", Long.class));
    }
}
