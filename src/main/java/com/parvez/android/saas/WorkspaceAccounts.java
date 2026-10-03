package com.parvez.android.saas;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Account provisioning and lookup; HTTP contracts live in WorkspaceController. */
@Service
public class WorkspaceAccounts implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    public WorkspaceAccounts(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }
    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc.query("SELECT email, password_hash, workspace_id, role FROM workspace_users WHERE email = ?",
                (rs, row) -> new WorkspacePrincipal(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)),
                email.strip().toLowerCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Transactional
    public Map<String, Object> register(Signup request) {
        UUID id = UUID.randomUUID();
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        jdbc.update("INSERT INTO workspaces (id, name) VALUES (?, ?)", id, request.workspaceName().strip());
        jdbc.update("INSERT INTO workspace_users (email, password_hash, workspace_id) VALUES (?, ?, ?)",
                email, passwords.encode(request.password()), id);
        return Map.of("workspaceId", id, "workspaceName", request.workspaceName().strip(), "email", email, "plan", "FREE");
    }
    @Transactional
    public void provisionSuperAdmin(String email, String password) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        // Serialize initial setup across replicas; never promote an existing owner or reset a password.
        jdbc.execute("SELECT pg_advisory_xact_lock(834872910)");
        var existing = jdbc.queryForList("SELECT role FROM workspace_users WHERE email = ?", String.class, normalized);
        if (!existing.isEmpty()) {
            if (!"SUPER_ADMIN".equals(existing.getFirst()))
                throw new IllegalStateException("Super Admin bootstrap email is already registered as a workspace account");
            return;
        }
        jdbc.update("INSERT INTO workspace_users (email, password_hash, workspace_id, role) VALUES (?, ?, NULL, 'SUPER_ADMIN')",
                normalized, passwords.encode(password));
    }
    public Map<String, Object> current() {
        return jdbc.queryForMap("SELECT id, name, plan, book_limit, (SELECT count(*) FROM books WHERE workspace_id = w.id) AS books_used FROM workspaces w WHERE id = ?",
                WorkspacePrincipal.currentWorkspace());
    }
    @Schema(name = "WorkspaceSignup", description = "Create an empty workspace and its owner account")
    public record Signup(@Schema(example="My Library") @NotBlank @Size(max=100) String workspaceName,
                         @Schema(description="Unique owner email; normalized to lowercase", example="owner@example.com") @NotBlank @Email @Size(max=254) String email,
                         @Schema(format="password", accessMode=Schema.AccessMode.WRITE_ONLY, description="12–64 characters", example="replace-this-password") @NotBlank @Size(min=12, max=64) String password) {}
}
