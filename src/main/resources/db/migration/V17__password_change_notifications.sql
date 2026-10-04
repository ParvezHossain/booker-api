-- Successful password replacements and their request context survive email delivery.
CREATE TABLE password_change_history (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    workspace_id UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    source VARCHAR(6) NOT NULL CHECK (source IN ('CHANGE', 'RESET')),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    browser VARCHAR(64) NOT NULL,
    device VARCHAR(64) NOT NULL
);
CREATE INDEX password_change_history_account ON password_change_history(email, changed_at);
CREATE INDEX password_change_history_workspace ON password_change_history(workspace_id, changed_at);

-- Only the receipt ID goes to RabbitMQ; no passwords or reset tokens are recorded.
CREATE TABLE password_change_emails (
    id UUID PRIMARY KEY REFERENCES password_change_history(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    failed_at TIMESTAMPTZ,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX password_change_emails_dispatch ON password_change_emails(available_at, created_at)
    WHERE failed_at IS NULL;
