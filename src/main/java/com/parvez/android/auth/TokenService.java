package com.parvez.android.auth;

import com.parvez.android.saas.WorkspaceAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TokenService {
    private static final String ISSUER = "booker-saas";
    private final WorkspaceAccounts accounts;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final String dummyPassword;

    public TokenService(WorkspaceAccounts accounts, PasswordEncoder passwords, JdbcTemplate jdbc,
                        @Value("${app.jwt.secret}") String secret,
                        @Value("${app.jwt.access-ttl}") Duration accessTtl,
                        @Value("${app.jwt.refresh-ttl}") Duration refreshTtl) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.jdbc = jdbc;
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 random bytes encoded as base64");
        if (accessTtl.toSeconds() < 1 || refreshTtl.compareTo(accessTtl) <= 0)
            throw new IllegalArgumentException("JWT lifetimes must be positive; refresh lifetime must exceed access lifetime");
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        dummyPassword = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Tokens login(String email, String password) {
        org.springframework.security.core.userdetails.UserDetails user;
        try {
            user = accounts.loadUserByUsername(email);
        } catch (AuthenticationException ex) {
            passwords.matches(password, dummyPassword);
            throw unauthorized();
        }
        if (!passwords.matches(password, user.getPassword())) throw unauthorized();
        return issue(user.getUsername());
    }

    @Transactional
    public Tokens refresh(String token) {
        Jwt jwt = decodeRefresh(token);
        // DELETE is atomic: concurrent refreshes can consume this token only once.
        int consumed = jdbc.update("DELETE FROM refresh_tokens WHERE token_hash = ? AND email = ? AND expires_at > ?",
                hash(token), jwt.getSubject(), Timestamp.from(Instant.now()));
        if (consumed != 1) throw unauthorized();
        try {
            return issue(accounts.loadUserByUsername(jwt.getSubject()).getUsername());
        } catch (AuthenticationException ex) {
            throw unauthorized();
        }
    }

    @Transactional
    public void logout(String token) {
        Jwt jwt = decodeRefresh(token);
        jdbc.update("DELETE FROM refresh_tokens WHERE token_hash = ? AND email = ?", hash(token), jwt.getSubject());
    }

    public Jwt decodeAccess(String token) {
        return decode(token, "access");
    }

    public UsernamePasswordAuthenticationToken authentication(Jwt jwt) {
        try {
            var user = accounts.loadUserByUsername(jwt.getSubject());
            return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        } catch (AuthenticationException ex) {
            throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException("Invalid account");
        }
    }

    private Jwt decodeRefresh(String token) {
        try { return decode(token, "refresh"); }
        catch (JwtException | IllegalArgumentException ex) { throw unauthorized(); }
    }

    private Jwt decode(String token, String type) {
        Jwt jwt = decoder.decode(token);
        if (!type.equals(jwt.getClaimAsString("token_type")) || jwt.getSubject() == null
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now()))
            throw new BadJwtException("Invalid token");
        return jwt;
    }

    private Tokens issue(String email) {
        Instant now = Instant.now();
        String access = encode(email, "access", now, accessTtl);
        String refresh = encode(email, "refresh", now, refreshTtl);
        jdbc.update("INSERT INTO refresh_tokens (token_hash, email, expires_at) VALUES (?, ?, ?)",
                hash(refresh), email, Timestamp.from(now.plus(refreshTtl)));
        return new Tokens(access, refresh, "Bearer", accessTtl.toSeconds(), refreshTtl.toSeconds());
    }

    private String encode(String email, String type, Instant now, Duration ttl) {
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(email).issuedAt(now)
                .expiresAt(now.plus(ttl)).id(UUID.randomUUID().toString()).claim("token_type", type).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or refresh token");
    }

    public record Tokens(String accessToken, String refreshToken, String tokenType,
                         long expiresIn, long refreshExpiresIn) {}
}
