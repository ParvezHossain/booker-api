package com.parvez.android.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Audit and durable notification acceptance in the password replacement transaction. */
@Component
public class PasswordChangeNotifications {
    private final JdbcTemplate jdbc;

    public PasswordChangeNotifications(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public enum Source { CHANGE, RESET }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String email, Source source, PasswordChangeContext context) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO password_change_history(id, email, workspace_id, source, ip_address, user_agent, browser, device)
                SELECT ?, email, workspace_id, ?, ?, ?, ?, ? FROM workspace_users WHERE email=?
                """, id, source.name(), context.ipAddress(), context.userAgent(), context.browser(), context.device(), email);
        if (inserted != 1) throw new IllegalStateException("Password change account unavailable");
        jdbc.update("INSERT INTO password_change_emails(id) VALUES (?)", id);
    }
}
