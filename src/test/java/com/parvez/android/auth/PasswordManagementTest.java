package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"management.health.mail.enabled=false","app.password-reset.from=books@example.com", "app.password-reset.url=",
        "app.password-reset.token-page-url=https://api.example.com/password-reset-token", "app.password-reset.monthly-limit=3",
        "app.password-reset.email.enabled=false", "app.password-reset.email.encryption-key=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="})
class PasswordManagementTest {
    @Autowired WorkspaceAccounts accounts;
    @Autowired TokenService tokens;
    @Autowired PasswordService passwords;
    @Autowired PasswordResetEmailWorker emailWorker;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    String email;
    final String old = "test-password-123";
    final String fresh = "new-password-1234";
    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        email = "password-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Password test", email, old));
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }
    @Test void changeRevokesAllSessionsAndValidatesCurrentPassword() throws Exception {
        var pair = tokens.login(email, old);
        String pending = requestToken();
        mvc.perform(post("/api/auth/change-password").contentType("application/json")
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\""+fresh+"\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer "+pair.accessToken())
                .contentType("application/json").content("{\"currentPassword\":\"wrong\",\"newPassword\":\""+fresh+"\"}"))
                .andExpect(status().isBadRequest());
        assertNotNull(tokens.decodeAccess(pair.accessToken()));
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer "+pair.accessToken())
                .contentType("application/json").content("{\"currentPassword\":\""+old+"\",\"newPassword\":\""+fresh+"\"}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer "+pair.accessToken())).andExpect(status().isUnauthorized());
        assertThrows(Exception.class, () -> tokens.refresh(pair.refreshToken()));
        assertThrows(Exception.class, () -> tokens.login(email, old));
        assertNotNull(tokens.login(email, fresh));
        assertThrows(Exception.class, () -> passwords.reset(pending, fresh));
    }
    @Test void resetEmailHashCooldownReplayAndRevocation() throws Exception {
        var pair = tokens.login(email, old);
        String token = requestToken();
        assertEquals(com.parvez.android.security.OpaqueTokens.sha256Hex(token), jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE email=?", String.class, email));
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\""+email+"\"}"))
                .andExpect(status().isAccepted());
        verify(mail, times(1)).send(any(MimeMessage.class));
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                .content(resetBody(token, fresh))).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                .content(resetBody(token, fresh))).andExpect(status().isBadRequest());
        assertEquals(1, resetCount(email));
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer "+pair.accessToken())).andExpect(status().isUnauthorized());
        assertThrows(Exception.class, () -> tokens.refresh(pair.refreshToken()));
        assertNotNull(tokens.login(email, fresh));
    }
    @Test void unknownEmailValidationAndExpiredToken() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value("If the account exists, a password reset email will be sent."));
        verifyNoInteractions(mail);
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        String token = requestToken();
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(resetBody(token,"short")))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE password_reset_tokens SET expires_at=now()-interval '1 second' WHERE email=?",email);
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(resetBody(token,fresh)))
                .andExpect(status().isBadRequest());
        assertNotNull(tokens.login(email,old));
        assertEquals(0, resetCount(email));
    }
    @Test void replacementAndConcurrentConsumption() throws Exception {
        String first = requestToken();
        jdbc.update("UPDATE password_reset_tokens SET requested_at=now()-interval '61 seconds' WHERE email=?",email);
        String second = requestToken();
        assertThrows(Exception.class, () -> passwords.reset(first,fresh));
        Callable<Boolean> consume = () -> { try { passwords.reset(second,fresh); return true; } catch (org.springframework.web.server.ResponseStatusException ex) { return false; } };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(consume,consume));
            assertNotEquals(results.get(0).get(),results.get(1).get());
        }
    }
    @Test void asynchronousMailFailurePreservesTokenForDelayedRetry() throws Exception {
        doThrow(new org.springframework.mail.MailSendException("delivery failed")).when(mail).send(any(MimeMessage.class));
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\""+email+"\"}"))
                .andExpect(status().isAccepted());
        verify(mail, never()).send(any(MimeMessage.class));
        UUID receipt = jdbc.queryForObject("SELECT id FROM password_reset_emails WHERE email=?", UUID.class, email);
        emailWorker.deliver(receipt);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE email=? AND expires_at > now()",Integer.class,email));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM password_reset_emails WHERE id=?", Integer.class, receipt));
        assertTrue(jdbc.queryForObject("SELECT available_at > clock_timestamp() AND lease_id IS NULL FROM password_reset_emails WHERE id=?", Boolean.class, receipt));
        assertEquals(0, resetCount(email));
    }
    @Test void superAdminCanChangePasswordWithBasicAuthentication() throws Exception {
        String admin = "admin-password-"+UUID.randomUUID()+"@example.com";
        accounts.provisionSuperAdmin(admin, old);
        mvc.perform(post("/api/auth/change-password")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(admin, old))
                .contentType("application/json").content("{\"currentPassword\":\""+old+"\",\"newPassword\":\""+fresh+"\"}"))
                .andExpect(status().isNoContent());
        assertNotNull(tokens.login(admin,fresh));
        assertThrows(Exception.class, () -> tokens.login(admin,old));
    }
    @Test void missingEmailConfigurationIsUnavailableForEveryAccount() {
        var unavailable = mock(PasswordResetDelivery.class);
        var service = new PasswordService(jdbc, context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class),
                unavailable, java.time.Duration.ofMinutes(30), 3);
        for (String account : List.of(email,"absent@example.com")) {
            var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.forgot(account));
            assertEquals(503,failure.getStatusCode().value());
        }
    }
    @Test void copyPageIsPublicUncachedAndDoesNotReflectTokenQueries() throws Exception {
        String queryToken = "B".repeat(43);
        var response = mvc.perform(get("/password-reset-token").param("token", queryToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", "clipboard-write=(self)"))
                .andReturn().getResponse();
        String html = response.getContentAsString();
        assertFalse(html.contains(queryToken));
        assertFalse(html.contains("{{nonce}}"));
        String policy = response.getHeader("Content-Security-Policy");
        assertNotNull(policy);
        assertTrue(policy.contains("default-src 'none'"));
        assertTrue(policy.contains("frame-ancestors 'none'"));
        String nonce = policy.split("script-src 'nonce-")[1].split("'")[0];
        assertTrue(html.contains("<script nonce=\"" + nonce + "\">"));
        assertTrue(html.contains("<style nonce=\"" + nonce + "\">"));
        String secondPolicy = mvc.perform(get("/password-reset-token")).andReturn().getResponse()
                .getHeader("Content-Security-Policy");
        assertNotEquals(policy, secondPolicy);
    }

    @Test void threeSuccessfulResetsExhaustAllowanceWithoutSendingMoreEmails() throws Exception {
        for (int i = 1; i <= 3; i++) {
            String token = requestToken();
            assertEquals(i - 1, resetCount(email), "Email issuance must not count");
            mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(resetBody(token, fresh)))
                    .andExpect(status().isNoContent());
            assertEquals(i, resetCount(email));
        }
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If the account exists, a password reset email will be sent."));
        verify(mail, times(3)).send(any(MimeMessage.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE email=?", Integer.class, email));
        assertNotNull(tokens.login(email, fresh));
    }

    @Test void exhaustedQuotaRejectsValidTokenButPreservesPasswordSessionsAndToken() throws Exception {
        var session = tokens.login(email, old);
        String token = requestToken();
        seedCurrentResets(email, 3);
        var response = mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(resetBody(token, fresh)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.path").value("/api/auth/reset-password"))
                .andExpect(jsonPath("$.message").value("You can reset your password at most 3 times per UTC calendar month"))
                .andReturn().getResponse();
        long retry = Long.parseLong(response.getHeader("Retry-After"));
        var now = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        var next = now.atZone(java.time.ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).plusMonths(1).toInstant();
        assertTrue(Math.abs(retry - java.time.Duration.between(now, next).toSeconds()) <= 2);
        assertEquals(com.parvez.android.security.OpaqueTokens.sha256Hex(token),
                jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE email=?", String.class, email));
        assertEquals(3, resetCount(email));
        assertNotNull(tokens.decodeAccess(session.accessToken()));
        assertNotNull(tokens.refresh(session.refreshToken()));
        assertNotNull(tokens.login(email, old));
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(resetBody("B".repeat(43), fresh)))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Retry-After"));
        jdbc.update("UPDATE password_reset_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE email=?", email);
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(resetBody(token, fresh)))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Retry-After"));
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        verify(mail, times(1)).send(any(MimeMessage.class));
    }

    @Test void priorAndFollowingMonthsDoNotUseCurrentAllowance() throws Exception {
        jdbc.update("""
                INSERT INTO password_reset_history(email, reset_at)
                SELECT ?, (date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') + elapsed
                FROM (VALUES (interval '-1 microsecond'), (interval '1 month'), (interval '1 month 1 second')) dates(elapsed)
                """, email);
        String token = requestToken();
        passwords.reset(token, fresh);
        assertEquals(4, resetCount(email));
        assertNotNull(tokens.login(email, fresh));
    }

    @Test void accountAllowanceIsIndependentAndAuthenticatedChangeDoesNotConsumeIt() throws Exception {
        seedCurrentResets(email, 3);
        String admin = "reset-admin-" + UUID.randomUUID() + "@example.com";
        accounts.provisionSuperAdmin(admin, old);
        String token = requestToken(admin);
        passwords.reset(token, fresh);
        assertEquals(1, resetCount(admin));
        assertEquals(3, resetCount(email));
        mvc.perform(post("/api/auth/change-password")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(email, old))
                .contentType("application/json").content("{\"currentPassword\":\"" + old + "\",\"newPassword\":\"" + fresh + "\"}"))
                .andExpect(status().isNoContent());
        assertEquals(3, resetCount(email));
        assertNotNull(tokens.login(email, fresh));
    }

    @Test void concurrentConsumptionCannotOverrunLastMonthlySlot() throws Exception {
        seedCurrentResets(email, 2);
        String token = requestToken();
        Callable<Integer> consume = () -> mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                .content(resetBody(token, fresh))).andReturn().getResponse().getStatus();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = pool.invokeAll(List.of(consume, consume, consume, consume));
            var statuses = results.stream().map(result -> {
                try { return result.get(); } catch (Exception ex) { throw new RuntimeException(ex); }
            }).toList();
            assertEquals(1, statuses.stream().filter(status -> status == 204).count());
            assertEquals(3, statuses.stream().filter(status -> status == 400).count());
        }
        assertEquals(3, resetCount(email));
    }

    @Test void rollbackRestoresQuotaPasswordTokenAndSessions() throws Exception {
        String token = requestToken();
        var session = tokens.login(email, old);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            passwords.reset(token, fresh);
            throw new IllegalStateException("Test-only rollback");
        }));
        assertEquals(0, resetCount(email));
        assertNotNull(tokens.decodeAccess(session.accessToken()));
        assertNotNull(tokens.refresh(session.refreshToken()));
        assertNotNull(tokens.login(email, old));
        passwords.reset(token, fresh);
        assertEquals(1, resetCount(email));
        assertNotNull(tokens.login(email, fresh));
    }

    @Test void configuredHigherAndLowerLimitsUseExistingCommittedHistory() throws Exception {
        String token = requestToken();
        seedCurrentResets(email, 3);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var encoder = context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
        var delivery = context.getBean(PasswordResetDelivery.class);
        var lower = new PasswordService(jdbc, encoder, delivery, java.time.Duration.ofMinutes(30), 1);
        var rejected = assertThrows(PasswordResetLimitExceededException.class,
                () -> transaction.executeWithoutResult(status -> lower.reset(token, fresh)));
        assertEquals(429, rejected.getStatusCode().value());
        assertTrue(rejected.getReason().contains("at most 1 times"));
        assertEquals(3, resetCount(email));
        var higher = new PasswordService(jdbc, encoder, delivery, java.time.Duration.ofMinutes(30), 5);
        transaction.executeWithoutResult(status -> higher.reset(token, fresh));
        assertEquals(4, resetCount(email));
        assertNotNull(tokens.login(email, fresh));
    }

    @Test void rollbackOfForgotCommitsNeitherTokenNorEncryptedReceipt() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            passwords.forgot(email);
            throw new IllegalStateException("Test-only rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE email=?", Integer.class, email));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_emails WHERE email=?", Integer.class, email));
        verifyNoInteractions(mail);
    }

    private int resetCount(String account) {
        return jdbc.queryForObject("SELECT count(*) FROM password_reset_history WHERE email=?", Integer.class, account);
    }

    private void seedCurrentResets(String account, int count) {
        jdbc.update("INSERT INTO password_reset_history(email, reset_at) SELECT ?, clock_timestamp() FROM generate_series(1, ?)", account, count);
    }

    private String requestToken() throws Exception {
        return requestToken(email);
    }
    private String requestToken(String recipient) throws Exception {
        int previousSends = (int) mockingDetails(mail).getInvocations().stream().filter(call -> call.getMethod().getName().equals("send")).count();
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\""+recipient.toUpperCase()+"\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.message").value("If the account exists, a password reset email will be sent."))
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(mail, times(previousSends)).send(any(MimeMessage.class));
        UUID receipt = jdbc.queryForObject("""
                SELECT e.id FROM password_reset_emails e JOIN password_reset_tokens t
                    ON t.email=e.email AND t.token_hash=e.token_hash WHERE e.email=?
                """, UUID.class, recipient);
        emailWorker.deliver(receipt);
        var capture = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail,atLeastOnce()).send(capture.capture());
        var alternatives = (Multipart) ((Multipart) capture.getValue().getContent()).getBodyPart(0).getContent();
        String text = alternatives.getBodyPart(0).getContent().toString();
        String token = text.split("Reset token:\n")[1].split("\n")[0];
        var expiresAt = jdbc.queryForObject("SELECT expires_at FROM password_reset_tokens WHERE email=?",
                java.sql.Timestamp.class, recipient).toInstant();
        assertTrue(text.contains("#token=" + token + "&expiresAt=" + expiresAt.toEpochMilli()));
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        return token;
    }
    private String resetBody(String token,String password) { return "{\"token\":\""+token+"\",\"newPassword\":\""+password+"\"}"; }
}
