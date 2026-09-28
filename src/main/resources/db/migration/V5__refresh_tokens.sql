CREATE TABLE refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_tokens_expiry ON refresh_tokens(expires_at);
