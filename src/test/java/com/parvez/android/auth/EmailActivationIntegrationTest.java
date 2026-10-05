package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"books.requests.email.enabled=false", "app.password-reset.email.enabled=false",
        "app.password-change.email.enabled=false", "app.email-activation.email.enabled=false",
        "app.email-activation.email.encryption-key=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=",
        "spring.autoconfigure.exclude=org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration,org.jobrunr.spring.autoconfigure.storage.JobRunrSqlStorageAutoConfiguration"})
class EmailActivationIntegrationTest {
    static final String schema = "activation_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("DATABASE_URL") + "?currentSchema=" + schema);
        p.add("spring.flyway.schemas", () -> schema); p.add("spring.flyway.default-schema", () -> schema);
    }
    @Autowired WorkspaceAccounts accounts;
    @Autowired EmailActivationService activation;
    @Autowired EmailActivationCipher cipher;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired WebApplicationContext context;
    @MockitoBean PasswordResetDelivery recovery;
    MockMvc mvc;
    String email;
    final String password = "test-password-123";

    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        email = "pending-" + UUID.randomUUID() + "@example.com";
        when(recovery.isConfigured()).thenReturn(true);
    }
    @AfterAll static void cleanup(@Autowired JdbcTemplate jdbc) { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
    WorkspaceAccounts.Registration signup() { return accounts.register(new WorkspaceAccounts.Signup("Pending workspace", email, password)); }
    String token() {
        var row = jdbc.queryForMap("SELECT e.* FROM email_activation_emails e JOIN email_activation_tokens t ON t.email=e.email AND t.token_hash=e.token_hash WHERE e.email=?", email);
        return cipher.decrypt((String) row.get("encrypted_token"), (UUID) row.get("id"), email,
                (String) row.get("token_hash"), ((Timestamp) row.get("expires_at")).toInstant());
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE email=?", Integer.class, email); }
    int activate(String value) throws Exception {
        return mvc.perform(post("/api/auth/activate").contentType("application/json").content("{\"token\":\"" + value + "\"}"))
                .andReturn().getResponse().getStatus();
    }
    void eligible() { jdbc.update("UPDATE email_activation_tokens SET requested_at=clock_timestamp()-interval '61 seconds' WHERE email=?", email); }

    @Test void signupQueuesProtectedTokenAndBlocksEveryAuthenticationUntilActivation() throws Exception {
        Instant before = Instant.now();
        var result = mvc.perform(post("/api/auth/signup").contentType("application/json")
                .content("{\"workspaceName\":\"Pending workspace\",\"email\":\"" + email.toUpperCase(java.util.Locale.ROOT) + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.activationRequired").value(true)).andReturn();
        var body = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        var expires = Instant.parse(body.path("activationExpiresAt").asText());
        assertTrue(expires.isAfter(before.plus(Duration.ofHours(24)).minusSeconds(1)));
        assertTrue(expires.isBefore(Instant.now().plus(Duration.ofHours(24)).plusSeconds(1)));
        String secret = token();
        assertFalse(result.getResponse().getContentAsString().contains(secret));
        assertEquals(43, secret.length());
        assertFalse(jdbc.queryForObject("SELECT encrypted_token FROM email_activation_emails WHERE email=?", String.class, email).contains(secret));
        assertFalse(accounts.loadUserByUsername(email).isEnabled());
        for (String supplied : List.of(password, "wrong")) {
            mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"" + supplied + "\"}"))
                    .andExpect(status().is(supplied.equals(password) ? 403 : 401));
        }
        mvc.perform(get("/api/workspace").with(httpBasic(email, password))).andExpect(status().isUnauthorized());
        assertEquals(0, count("refresh_tokens")); assertEquals(0, count("login_history"));
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        verify(recovery, never()).enqueue(anyString(), anyString(), any());
    }

    @Test void activationIsExplicitSingleUseAndDoesNotIssueSessionsOrChangePassword() throws Exception {
        var registration = signup(); String value = token();
        mvc.perform(get("/api/auth/activate").param("token", value)).andExpect(status().isUnauthorized());
        assertFalse(accounts.loadUserByUsername(email).isEnabled());
        assertEquals(204, activate(value));
        assertTrue(accounts.loadUserByUsername(email).isEnabled());
        assertNotNull(jdbc.queryForObject("SELECT email_verified_at FROM workspace_users WHERE email=?", Timestamp.class, email));
        assertEquals(0, count("email_activation_tokens")); assertEquals(0, count("email_activation_emails"));
        assertEquals(0, count("refresh_tokens")); assertEquals(400, activate(value));
        var pair = tokens.login(email, password);
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(registration.workspaceId().toString()));
        mvc.perform(get("/api/admin/login-history").header("Authorization", "Bearer " + pair.accessToken())).andExpect(status().isForbidden());
    }

    @Test void expiredAndWrongPurposeTokensStayInvalidAndResendReplacesOnlyTheChallenge() throws Exception {
        var registration = signup(); String oldToken = token();
        String hash = jdbc.queryForObject("SELECT password_hash FROM workspace_users WHERE email=?", String.class, email);
        jdbc.update("UPDATE email_activation_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE email=?", email);
        assertEquals(400, activate(oldToken));
        String reset = "R".repeat(43);
        jdbc.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at) VALUES (?,?,clock_timestamp()+interval '1 hour')", email, com.parvez.android.security.OpaqueTokens.sha256Hex(reset));
        assertEquals(400, activate(reset));
        eligible(); activation.resend(email.toUpperCase(java.util.Locale.ROOT)); String fresh = token();
        assertNotEquals(oldToken, fresh); assertEquals(400, activate(oldToken));
        assertEquals(registration.workspaceId(), jdbc.queryForObject("SELECT workspace_id FROM workspace_users WHERE email=?", UUID.class, email));
        assertEquals(hash, jdbc.queryForObject("SELECT password_hash FROM workspace_users WHERE email=?", String.class, email));
        assertEquals(204, activate(fresh));
    }

    @Test void resendIsGenericAndCooldownIsEnforcedOnServer() throws Exception {
        signup(); String original = token();
        for (String account : List.of(email, "missing@example.com")) {
            mvc.perform(post("/api/auth/resend-activation").contentType("application/json").content("{\"email\":\"" + account + "\"}"))
                    .andExpect(status().isAccepted()).andExpect(content().string(""));
        }
        assertEquals(original, token()); assertEquals(1, count("email_activation_emails"));
        assertEquals(204, activate(original));
        mvc.perform(post("/api/auth/resend-activation").contentType("application/json").content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertEquals(0, count("email_activation_tokens"));
    }

    @Test void concurrentResendsAndActivationsHaveOneWinner() throws Exception {
        signup(); eligible();
        try (var pool = Executors.newFixedThreadPool(3)) {
            for (var result : pool.invokeAll(List.<java.util.concurrent.Callable<Void>>of(
                    () -> { activation.resend(email); return null; }, () -> { activation.resend(email); return null; }, () -> { activation.resend(email); return null; })))
                result.get(10, TimeUnit.SECONDS);
            assertEquals(2, count("email_activation_emails"));
            String current = token();
            var outcomes = pool.invokeAll(List.of(() -> activate(current), () -> activate(current)));
            assertEquals(List.of(204, 400), outcomes.stream().map(result -> assertDoesNotThrow(() -> result.get(10, TimeUnit.SECONDS))).sorted().toList());
        }
    }

    @Test void outerRollbackAndReceiptFailureLeaveNoOrphanWorkspaceOrAccount() {
        int workspaces = jdbc.queryForObject("SELECT count(*) FROM workspaces", Integer.class);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> { signup(); throw new IllegalStateException("test-only rollback"); }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workspace_users WHERE email=?", Integer.class, email));
        jdbc.execute("CREATE FUNCTION reject_activation_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only receipt rejection'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_activation_receipt BEFORE INSERT ON email_activation_emails FOR EACH ROW EXECUTE FUNCTION reject_activation_receipt()");
        try { assertThrows(RuntimeException.class, this::signup); }
        finally { jdbc.execute("DROP TRIGGER reject_activation_receipt ON email_activation_emails"); jdbc.execute("DROP FUNCTION reject_activation_receipt()"); }
        assertEquals(workspaces, jdbc.queryForObject("SELECT count(*) FROM workspaces", Integer.class));
        assertEquals(0, count("email_activation_tokens")); assertEquals(0, count("email_activation_emails"));
    }

    @Test void rollbackRestoresConsumedTokenAndPendingAccount() throws Exception {
        signup(); String value = token();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> { activation.activate(value); throw new IllegalStateException("test-only rollback"); }));
        assertFalse(accounts.loadUserByUsername(email).isEnabled()); assertEquals(1, count("email_activation_tokens"));
        assertEquals(204, activate(value));
    }

    @Test void configurationControlsExpiryAndMissingKeyRejectsIssuance() {
        signup();
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT email FROM workspace_users WHERE email=? FOR UPDATE", String.class, email);
            Instant expiry = new EmailActivationService(jdbc, cipher, Duration.ofHours(6)).issue(email);
            assertTrue(Duration.between(Instant.now(), expiry).minus(Duration.ofHours(6)).abs().compareTo(Duration.ofSeconds(2)) < 0);
        });
        var unavailable = new EmailActivationService(jdbc, new EmailActivationCipher(""), Duration.ofDays(1));
        assertEquals(503, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> unavailable.resend(email)).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> new EmailActivationService(jdbc, cipher, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new EmailActivationService(jdbc, cipher, Duration.ofDays(8)));
    }

    @Test void inactiveAccountCannotUsePreviouslyIssuedBearerOrRefreshTokens() throws Exception {
        signup(); assertEquals(204, activate(token())); var pair = tokens.login(email, password);
        jdbc.update("UPDATE workspace_users SET email_verified=FALSE,email_verified_at=NULL WHERE email=?", email);
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + pair.accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"" + pair.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void validationAndDuplicateSignupPreserveOriginalPendingChallenge() throws Exception {
        signup(); String original = token();
        mvc.perform(post("/api/auth/signup").contentType("application/json").content("{\"workspaceName\":\"Other\",\"email\":\"" + email + "\",\"password\":\"different-password\"}"))
                .andExpect(status().isConflict());
        assertEquals(original, token());
        for (String body : List.of("{}", "{\"token\":\"short\"}")) mvc.perform(post("/api/auth/activate").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/resend-activation").contentType("application/json").content("{\"email\":\"bad\"}")).andExpect(status().isBadRequest());
    }
}
