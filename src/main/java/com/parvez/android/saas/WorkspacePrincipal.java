package com.parvez.android.saas;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import java.util.List;
import java.util.UUID;

public class WorkspacePrincipal extends User {
    private final UUID workspaceId;
    private final String role;
    public WorkspacePrincipal(String email, String password, UUID workspaceId) {
        this(email, password, workspaceId, "OWNER");
    }
    public WorkspacePrincipal(String email, String password, UUID workspaceId, String role) {
        this(email, password, workspaceId, role, true);
    }
    public WorkspacePrincipal(String email, String password, UUID workspaceId, String role, boolean enabled) {
        super(email, password, enabled, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.role = role;
        this.workspaceId = workspaceId;
    }
    public String getRole() { return role; }
    public static UUID currentWorkspace() {
        UUID workspace = current().workspaceId;
        if (workspace == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "A workspace account is required");
        return workspace;
    }
    public static void requireSuperAdmin() {
        if (!"SUPER_ADMIN".equals(current().role)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "Super Admin access is required");
    }
    public static String currentEmail() { return current().getUsername(); }
    public static WorkspacePrincipal current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof WorkspacePrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Workspace authentication required");
        }
        return principal;
    }
}
