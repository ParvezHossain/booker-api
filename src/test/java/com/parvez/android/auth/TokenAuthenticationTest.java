package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class TokenAuthenticationTest {
    @Autowired TokenService tokens;
    @Autowired WorkspaceAccounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Value("${app.jwt.secret}") String secret;
    MockMvc mvc;
    String email;
    UUID workspace;

    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        email = "jwt-" + UUID.randomUUID() + "@example.com";
        workspace = com.parvez.android.TestAccounts.registerVerified(accounts, jdbc, new WorkspaceAccounts.Signup("JWT test", email, "test-password-123")).workspaceId();
    }

    @Test void loginBearerWorkspaceAndValidation() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").isString()).andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.expiresIn").value(900));
        var pair = tokens.login(email.toUpperCase(), "test-password-123");
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(workspace.toString()));
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + pair.refreshToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + pair.accessToken() + "tampered"))
                .andExpect(status().isUnauthorized());
        for (String loginEmail : List.of(email, "missing@example.com")) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                    .content("{\"email\":\"" + loginEmail + "\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(body("invalid")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(body(pair.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test void rotationLogoutAndDatabaseExpiry() throws Exception {
        var httpPair = tokens.login(email, "test-password-123");
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(body(httpPair.refreshToken())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").isString()).andExpect(jsonPath("$.refreshToken").isString());
        jdbc.update("DELETE FROM refresh_tokens WHERE email = ?", email);
        var first = tokens.login(email, "test-password-123");
        var second = tokens.refresh(first.refreshToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertThrows(ResponseStatusException.class, () -> tokens.refresh(first.refreshToken()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE email = ?", Integer.class, email));
        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE email = ?", String.class, email);
        assertEquals(64, stored.length());
        assertNotEquals(second.refreshToken(), stored);
        mvc.perform(post("/api/auth/logout").contentType("application/json").content(body(second.refreshToken())))
                .andExpect(status().isNoContent());
        assertThrows(ResponseStatusException.class, () -> tokens.refresh(second.refreshToken()));
        var third = tokens.login(email, "test-password-123");
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second' WHERE email = ?", email);
        assertThrows(ResponseStatusException.class, () -> tokens.refresh(third.refreshToken()));
    }

    @Test void concurrentRefreshHasExactlyOneWinner() throws Exception {
        var pair = tokens.login(email, "test-password-123");
        Callable<Boolean> refresh = () -> {
            try { tokens.refresh(pair.refreshToken()); return true; }
            catch (ResponseStatusException ex) { assertEquals(401, ex.getStatusCode().value()); return false; }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(refresh, refresh));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        }
    }

    @Test void expiredJwtAndDeletedAccountAreRejected() throws Exception {
        var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var claims = JwtClaimsSet.builder().issuer("booker-saas").subject(email)
                .issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().minusSeconds(1))
                .claim("token_type", "access").build();
        String expired = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        var pair = tokens.login(email, "test-password-123");
        jdbc.update("DELETE FROM workspace_users WHERE email = ?", email);
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isUnauthorized());
        assertThrows(ResponseStatusException.class, () -> tokens.refresh(pair.refreshToken()));
    }

    private static String body(String token) { return "{\"refreshToken\":\"" + token + "\"}"; }
}
