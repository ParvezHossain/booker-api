package com.parvez.android.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Successful login metadata commits with token issuance, without recording credentials. */
@Component
public class LoginAudit {
    private final JdbcTemplate jdbc;

    public LoginAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String email, PasswordChangeContext context) {
        int inserted = jdbc.update("""
                INSERT INTO login_history(id, email, workspace_id, ip_address, user_agent, browser, device)
                SELECT ?, email, workspace_id, ?, ?, ?, ? FROM workspace_users WHERE email=?
                """, UUID.randomUUID(), context.ipAddress(), context.userAgent(), context.browser(), context.device(), email);
        if (inserted != 1) throw new IllegalStateException("Login audit account unavailable");
    }
}
