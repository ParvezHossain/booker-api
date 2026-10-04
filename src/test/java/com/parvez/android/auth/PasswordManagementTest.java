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
        "app.password-reset.token-page-url=https://api.example.com/password-reset-token"})
class PasswordManagementTest {
    @Autowired WorkspaceAccounts accounts;
    @Autowired TokenService tokens;
    @Autowired PasswordService passwords;
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
    @Test void mailFailureDoesNotRevealAccountOrLeaveToken() throws Exception {
        doThrow(new org.springframework.mail.MailSendException("delivery failed")).when(mail).send(any(MimeMessage.class));
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\""+email+"\"}"))
                .andExpect(status().isAccepted());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE email=? AND expires_at > now()",Integer.class,email));
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
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var service = new PasswordService(jdbc, context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class),
                new SmtpPasswordResetDelivery(factory.getBeanProvider(JavaMailSender.class), "", "", "",
                        context.getBean(PasswordResetEmailTemplate.class)), java.time.Duration.ofMinutes(30));
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
    private String requestToken() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"email\":\""+email.toUpperCase()+"\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.message").value("If the account exists, a password reset email will be sent."))
                .andExpect(jsonPath("$.token").doesNotExist());
        var capture = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail,atLeastOnce()).send(capture.capture());
        var alternatives = (Multipart) ((Multipart) capture.getValue().getContent()).getBodyPart(0).getContent();
        String text = alternatives.getBodyPart(0).getContent().toString();
        String token = text.split("Reset token:\n")[1].split("\n")[0];
        var expiresAt = jdbc.queryForObject("SELECT expires_at FROM password_reset_tokens WHERE email=?",
                java.sql.Timestamp.class, email).toInstant();
        assertTrue(text.contains("#token=" + token + "&expiresAt=" + expiresAt.toEpochMilli()));
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        return token;
    }
    private String resetBody(String token,String password) { return "{\"token\":\""+token+"\",\"newPassword\":\""+password+"\"}"; }
}
