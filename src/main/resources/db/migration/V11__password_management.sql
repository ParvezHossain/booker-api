ALTER TABLE workspace_users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 CHECK (credential_version >= 0);
CREATE TABLE password_reset_tokens (
    email VARCHAR(254) PRIMARY KEY REFERENCES workspace_users(email) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_tokens_expiry ON password_reset_tokens(expires_at);
