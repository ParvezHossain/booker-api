-- Successful credential logins only; issuance and audit share the account transaction.
CREATE TABLE login_history (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    workspace_id UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    logged_in_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    browser VARCHAR(64) NOT NULL,
    device VARCHAR(64) NOT NULL
);
CREATE INDEX login_history_recent ON login_history(logged_in_at DESC, id DESC);
CREATE INDEX login_history_workspace_recent ON login_history(workspace_id, logged_in_at DESC, id DESC);
CREATE INDEX login_history_account_recent ON login_history(email, logged_in_at DESC, id DESC);
-- Keep existing V17 audit data and receipt ownership intact.
CREATE INDEX password_change_history_recent ON password_change_history(changed_at DESC, id DESC);
CREATE INDEX password_change_history_workspace_recent ON password_change_history(workspace_id, changed_at DESC, id DESC);
