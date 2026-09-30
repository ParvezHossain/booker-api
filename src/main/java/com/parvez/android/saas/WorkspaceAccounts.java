package com.parvez.android.saas;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.parvez.android.dto.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

@RestController
@RequestMapping("/api")
@Tag(name = "Workspaces", description = "Workspace signup and current plan usage")
class WorkspaceController {
    private final WorkspaceAccounts accounts;
    WorkspaceController(WorkspaceAccounts accounts) { this.accounts = accounts; }
    @Operation(summary = "Create a workspace and owner account", description = "Public endpoint; authentication is not required. Creates an empty FREE workspace with a 100-book limit. Passwords are stored as salted PBKDF2 hashes. Log in at POST /api/auth/login to obtain access and refresh tokens.")
    @ApiResponse(responseCode = "201", description = "Workspace created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SignupResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid signup fields or JSON", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Email already registered; no workspace is created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signup(@Valid @RequestBody WorkspaceAccounts.Signup request) {
        return accounts.register(request);
    }
    @Operation(summary = "Get your workspace and usage", description = "Returns the authenticated owner's workspace, current entitlement and number of books. Plan changes are performed by an operator; this API does not process payments.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @ApiResponse(responseCode = "200", description = "Current workspace", content = @Content(mediaType = "application/json", schema = @Schema(implementation = WorkspaceResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid owner credentials", content = @Content)
    @GetMapping("/workspace")
    public Map<String, Object> workspace() { return accounts.current(); }

    @Schema(name = "SignupResponse")
    public record SignupResponse(UUID workspaceId,
                                 @Schema(example="My Library") String workspaceName,
                                 @Schema(example="owner@example.com") String email,
                                 @Schema(allowableValues={"FREE"}, example="FREE") String plan) {}

    @Schema(name = "WorkspaceResponse")
    public record WorkspaceResponse(UUID id,
                                    @Schema(example="My Library") String name,
                                    @Schema(allowableValues={"FREE", "PRO"}, example="FREE") String plan,
                                    @Schema(description="Maximum books allowed", example="100") int book_limit,
                                    @Schema(description="Books currently stored", example="1") long books_used) {}

}
