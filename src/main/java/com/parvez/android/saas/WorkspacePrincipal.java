package com.parvez.android.saas;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import java.util.List;
import java.util.UUID;

public class WorkspacePrincipal extends User {
    private final UUID workspaceId;
    public WorkspacePrincipal(String email, String password, UUID workspaceId) {
        super(email, password, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        this.workspaceId = workspaceId;
    }
    public static UUID currentWorkspace() {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof WorkspacePrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Workspace authentication required");
        }
        return principal.workspaceId;
    }
}
