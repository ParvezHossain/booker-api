package com.parvez.android.library;

import com.parvez.android.saas.*;
import com.parvez.android.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.super-admin.email=bootstrap-admin@example.com", "app.super-admin.password=bootstrap-password-123"})
class SuperAdminBootstrapTest {
    @Autowired WorkspaceAccounts accounts;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Test void startupProvisionsStandaloneAdminAndUsesNormalLogin() {
        var principal = (WorkspacePrincipal) accounts.loadUserByUsername("bootstrap-admin@example.com");
        assertEquals("SUPER_ADMIN", principal.getRole());
        assertTrue(principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN")));
        assertNull(jdbc.queryForMap("SELECT workspace_id FROM workspace_users WHERE email = ?", principal.getUsername()).get("workspace_id"));
        assertTrue(passwords.matches("bootstrap-password-123", principal.getPassword()));
        assertNotNull(tokens.login(principal.getUsername(), "bootstrap-password-123").accessToken());
        String hash = principal.getPassword();
        accounts.provisionSuperAdmin("BOOTSTRAP-ADMIN@EXAMPLE.COM", "different-password-123");
        assertEquals(hash, accounts.loadUserByUsername(principal.getUsername()).getPassword());
    }
    @Test void configurationCannotPromoteAnExistingWorkspaceOwner() {
        String email = "existing-" + UUID.randomUUID() + "@example.com";
        accounts.register(new WorkspaceAccounts.Signup("Existing", email, "owner-password-123"));
        assertThrows(IllegalStateException.class, () -> accounts.provisionSuperAdmin(email, "bootstrap-password-123"));
        assertEquals("OWNER", ((WorkspacePrincipal) accounts.loadUserByUsername(email)).getRole());
    }
    @Test void incompleteOrWeakBootstrapSettingsFailWithoutCreatingAccounts() {
        String email = "invalid-bootstrap-" + UUID.randomUUID() + "@example.com";
        assertThrows(IllegalStateException.class, () -> new SuperAdminBootstrap(accounts, email, "short").run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class, () -> new SuperAdminBootstrap(accounts, "", "bootstrap-password-123").run(new DefaultApplicationArguments()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workspace_users WHERE email = ?", Integer.class, email));
    }
}
