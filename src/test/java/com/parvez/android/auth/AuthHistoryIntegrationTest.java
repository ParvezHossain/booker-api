package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import com.parvez.android.saas.WorkspacePrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"books.requests.email.enabled=false", "app.password-reset.email.enabled=false",
        "app.password-change.email.enabled=false",
        "spring.autoconfigure.exclude=org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration,org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration"})
class AuthHistoryIntegrationTest {
    static final String schema = "auth_history_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("DATABASE_URL") + "?currentSchema=" + schema);
        properties.add("spring.flyway.schemas", () -> schema);
        properties.add("spring.flyway.default-schema", () -> schema);
    }
    @Autowired WorkspaceAccounts accounts;
    @Autowired TokenService tokens;
    @Autowired PasswordService passwords;
    @Autowired AuthHistoryService history;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String owner, member, other, admin;
    UUID workspace, otherWorkspace;
    final String old = "test-password-123";
    final String fresh = "new-password-1234";
    final JsonMapper json = JsonMapper.builder().build();
    final PasswordChangeContext client = new PasswordChangeContext("203.0.113.5", "Mozilla/5.0 (Windows NT 10.0) Chrome/154.0");

    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM login_history");
        jdbc.update("DELETE FROM password_change_history");
        String suffix = UUID.randomUUID().toString();
        owner = "audit-owner-" + suffix + "@example.com";
        member = "audit-member-" + suffix + "@example.com";
        other = "audit-other-" + suffix + "@example.com";
        admin = "audit-admin-" + suffix + "@example.com";
        workspace = (UUID) accounts.register(new WorkspaceAccounts.Signup("Audit workspace", owner, old)).get("workspaceId");
        jdbc.update("INSERT INTO workspace_users(email,password_hash,workspace_id) SELECT ?,password_hash,workspace_id FROM workspace_users WHERE email=?", member, owner);
        otherWorkspace = (UUID) accounts.register(new WorkspaceAccounts.Signup("Other audit workspace", other, old)).get("workspaceId");
        accounts.provisionSuperAdmin(admin, old);
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    @AfterAll static void cleanup(@Autowired JdbcTemplate jdbc) { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    int loginCount(String email) { return jdbc.queryForObject("SELECT count(*) FROM login_history WHERE email=?", Integer.class, email); }
    JsonNode read(String path, String email, String password) throws Exception {
        var response = mvc.perform(get(path).with(httpBasic(email, password)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }
    void seedAll() {
        for (String email : List.of(owner, member, other, admin)) {
            tokens.login(email, old, client);
            passwords.change(email, old, fresh, client);
        }
    }

    @Test void successfulLoginCapturesRequestContextButFailedLoginRefreshAndBasicReadsDoNot() throws Exception {
        for (String email : List.of(owner, "missing@example.com")) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                    .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, loginCount(owner));
        String agent = "Mozilla/5.0 (Linux x86_64) Chrome/154.0 " + "x".repeat(600);
        var response = mvc.perform(post("/api/auth/login")
                .with(request -> { request.setRemoteAddr("2001:db8::12"); return request; })
                .header("User-Agent", agent).header("X-Forwarded-For", "198.51.100.9").header("Forwarded", "for=198.51.100.10")
                .contentType("application/json").content("{\"email\":\"" + owner.toUpperCase(java.util.Locale.ROOT) + "\",\"password\":\"" + old + "\"}"))
                .andExpect(status().isOk()).andReturn();
        var pair = json.readTree(response.getResponse().getContentAsString());
        tokens.refresh(pair.path("refreshToken").asText());
        mvc.perform(get("/api/workspace/login-history").header("Authorization", "Bearer " + pair.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].email").value(owner))
                .andExpect(jsonPath("$.items[0].workspaceId").value(workspace.toString()))
                .andExpect(jsonPath("$.items[0].workspaceName").value("Audit workspace"))
                .andExpect(jsonPath("$.items[0].ipAddress").value("2001:db8::12"))
                .andExpect(jsonPath("$.items[0].browser").value("Chrome"))
                .andExpect(jsonPath("$.items[0].device").value("Computer / Linux"))
                .andExpect(jsonPath("$.items[0].userAgent").value(agent.substring(0, 512)));
        read("/api/workspace/login-history", owner, old);
        assertEquals(1, loginCount(owner));
        assertEquals(0, loginCount(other));
    }

    @Test void workspaceReadsIncludeItsAccountsOnlyAndCannotBeRedirectedBySelectors() throws Exception {
        seedAll();
        for (String suffix : List.of("login-history", "password-change-history")) {
            var page = read("/api/workspace/" + suffix + "?workspaceId=" + otherWorkspace + "&email=" + other + "&role=SUPER_ADMIN", owner, fresh);
            assertEquals(2, page.path("items").size());
            var emails = new HashSet<String>();
            for (var entry : page.path("items")) {
                assertEquals(workspace.toString(), entry.path("workspaceId").asText());
                emails.add(entry.path("email").asText());
                for (String secret : List.of("password_hash", "passwordHash", "token_hash", "refreshToken", "encrypted_token", "encryptedToken"))
                    assertFalse(entry.has(secret));
            }
            assertEquals(new HashSet<>(List.of(owner, member)), emails);
            assertTrue(page.path("nextCursor").isNull());
        }
    }

    @Test void superAdminReadsEveryWorkspaceAndCanFilterButWorkspaceEndpointsRemainForbidden() throws Exception {
        seedAll();
        for (String suffix : List.of("login-history", "password-change-history")) {
            var all = read("/api/admin/" + suffix, admin, fresh);
            assertEquals(4, all.path("items").size());
            var emails = new HashSet<String>();
            for (var entry : all.path("items")) {
                emails.add(entry.path("email").asText());
                if (entry.path("email").asText().equals(admin)) {
                    assertTrue(entry.path("workspaceId").isNull());
                    assertTrue(entry.path("workspaceName").isNull());
                }
            }
            assertEquals(new HashSet<>(List.of(owner, member, other, admin)), emails);
            var filtered = read("/api/admin/" + suffix + "?workspaceId=" + otherWorkspace, admin, fresh);
            assertEquals(1, filtered.path("items").size());
            assertEquals(other, filtered.path("items").get(0).path("email").asText());
            assertEquals(0, read("/api/admin/" + suffix + "?workspaceId=" + UUID.randomUUID(), admin, fresh).path("items").size());
            mvc.perform(get("/api/workspace/" + suffix).with(httpBasic(admin, fresh))).andExpect(status().isForbidden());
        }
    }

    @Test void authenticationAndServiceChecksProtectAdminHistoryBeforeCursorLookup() throws Exception {
        for (String suffix : List.of("login-history", "password-change-history")) {
            mvc.perform(get("/api/workspace/" + suffix)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/" + suffix)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/" + suffix).with(httpBasic(owner, old)))
                    .andExpect(status().isForbidden());
        }
        var principal = new WorkspacePrincipal(owner, "", workspace);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        for (Runnable call : List.<Runnable>of(() -> history.allLogins(otherWorkspace, 50, UUID.randomUUID()),
                () -> history.allPasswordChanges(otherWorkspace, 50, UUID.randomUUID()))) {
            var rejected = assertThrows(ResponseStatusException.class, call::run);
            assertEquals(403, rejected.getStatusCode().value());
        }
    }

    @Test void invalidLimitsMalformedUuidsAndForeignCursorsAreSafeBadRequests() throws Exception {
        seedAll();
        UUID foreignLogin = jdbc.queryForObject("SELECT id FROM login_history WHERE email=?", UUID.class, other);
        UUID foreignChange = jdbc.queryForObject("SELECT id FROM password_change_history WHERE email=?", UUID.class, other);
        for (String suffix : List.of("login-history", "password-change-history")) {
            String ownPath = "/api/workspace/" + suffix;
            for (String query : List.of("limit=0", "limit=-1", "limit=101", "limit=nope", "cursor=bad-uuid", "cursor=" + UUID.randomUUID())) {
                mvc.perform(get(ownPath + "?" + query).with(httpBasic(owner, fresh))).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.status").value(400));
            }
            mvc.perform(get(ownPath + "?cursor=" + (suffix.equals("login-history") ? foreignLogin : foreignChange))
                    .with(httpBasic(owner, fresh))).andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/" + suffix + "?workspaceId=invalid").with(httpBasic(admin, fresh)))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/" + suffix + "?workspaceId=" + workspace + "&cursor="
                    + (suffix.equals("login-history") ? foreignLogin : foreignChange)).with(httpBasic(admin, fresh)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/workspace/login-history?cursor=" + jdbc.queryForObject("SELECT id FROM password_change_history WHERE email=?", UUID.class, owner))
                .with(httpBasic(owner, fresh))).andExpect(status().isBadRequest());
    }

    @Test void cursorPagesHaveDeterministicTiesAndDoNotRepeatWhenNewLoginsArrive() throws Exception {
        for (int i = 1; i <= 6; i++) {
            UUID id = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(i));
            jdbc.update("INSERT INTO login_history(id,email,workspace_id,logged_in_at,browser,device) VALUES (?,?,?,'2026-01-01T00:00:00Z','Unknown','Unknown')", id, owner, workspace);
            jdbc.update("INSERT INTO password_change_history(id,email,workspace_id,changed_at,source,browser,device) VALUES (?,?,?,'2026-01-01T00:00:00Z','CHANGE','Unknown','Unknown')", id, owner, workspace);
        }
        for (String suffix : List.of("login-history", "password-change-history")) {
            var seen = new ArrayList<String>();
            String cursor = null;
            do {
                String path = "/api/workspace/" + suffix + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
                var page = read(path, owner, old);
                assertEquals(2, page.path("items").size());
                for (var entry : page.path("items")) seen.add(entry.path("id").asText());
                if (cursor == null && suffix.equals("login-history")) tokens.login(owner, old, client);
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            } while (cursor != null);
            assertEquals(6, seen.size());
            assertEquals(6, new HashSet<>(seen).size());
            assertEquals(seen.stream().sorted(java.util.Comparator.reverseOrder()).toList(), seen);
        }
    }

    @Test void passwordRecoveryAndAuthenticatedChangesRemainDistinctAndSurviveReceiptRemoval() throws Exception {
        passwords.change(owner, old, fresh, client);
        String token = "R".repeat(43);
        jdbc.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at) VALUES (?,?,clock_timestamp()+interval '30 minutes')",
                owner, com.parvez.android.security.OpaqueTokens.sha256Hex(token));
        passwords.reset(token, old, new PasswordChangeContext("198.51.100.4", "Firefox/155.0"));
        jdbc.update("DELETE FROM password_change_emails");
        var entries = read("/api/workspace/password-change-history", owner, old).path("items");
        assertEquals(2, entries.size());
        assertEquals("RESET", entries.get(0).path("source").asText());
        assertEquals("198.51.100.4", entries.get(0).path("ipAddress").asText());
        assertEquals("CHANGE", entries.get(1).path("source").asText());
    }

    @Test void auditFailureAndExplicitRollbackCannotLeaveUnauditedRefreshTokens() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            tokens.login(owner, old, client);
            throw new IllegalStateException("Test-only rollback");
        }));
        assertEquals(0, loginCount(owner));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE email=?", Integer.class, owner));
        jdbc.execute("CREATE FUNCTION reject_login_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only audit failure'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_login_audit BEFORE INSERT ON login_history FOR EACH ROW EXECUTE FUNCTION reject_login_audit()");
        try {
            assertThrows(RuntimeException.class, () -> tokens.login(owner, old, client));
            assertEquals(0, loginCount(owner));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE email=?", Integer.class, owner));
        } finally {
            jdbc.execute("DROP TRIGGER reject_login_audit ON login_history");
            jdbc.execute("DROP FUNCTION reject_login_audit()");
        }
        assertNotNull(tokens.login(owner, old, client));
    }

    @Test void auditCaptureDoesNotWaitForWorkspaceQuotaLocks() {
        try (var pool = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE", UUID.class, workspace);
                var result = pool.submit(() -> {
                    tokens.login(owner, old, client);
                    passwords.change(owner, old, fresh, client);
                });
                // Complete while another transaction owns the workspace row lock.
                // Otherwise account-first audit capture can deadlock workspace-first requests.
                assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
            });
        }
        assertEquals(1, loginCount(owner));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM password_change_history WHERE email=?", Integer.class, owner));
    }

    @Test void concurrentLoginsEachCommitOneAuditAndTokenPair() throws Exception {
        try (var pool = Executors.newFixedThreadPool(3)) {
            var results = pool.invokeAll(List.of(() -> tokens.login(owner, old, client),
                    () -> tokens.login(owner, old, client), () -> tokens.login(owner, old, client)));
            for (var result : results) assertNotNull(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(3, loginCount(owner));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE email=?", Integer.class, owner));
    }
}
