package com.parvez.android;

import com.parvez.android.saas.WorkspaceAccounts;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixtures for features whose prerequisite is an already activated account. */
public final class TestAccounts {
    private TestAccounts() {}
    public static WorkspaceAccounts.Registration registerVerified(WorkspaceAccounts accounts, JdbcTemplate jdbc,
                                                                  WorkspaceAccounts.Signup request) {
        var result = accounts.register(request);
        activateFixture(jdbc, result.email());
        return result;
    }
    public static void activateFixture(JdbcTemplate jdbc, String email) {
        jdbc.update("UPDATE workspace_users SET email_verified=TRUE,email_verified_at=clock_timestamp() WHERE email=?", email);
        jdbc.update("DELETE FROM email_activation_emails WHERE email=?", email);
        jdbc.update("DELETE FROM email_activation_tokens WHERE email=?", email);
    }
}
