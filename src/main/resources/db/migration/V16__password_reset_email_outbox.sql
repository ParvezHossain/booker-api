-- Raw recovery secrets are never broker payloads or plaintext database values.
-- Append-only issuance avoids waiting on a publisher/consumer's older receipt lock.
CREATE TABLE password_reset_emails (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    encrypted_token TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    failed_at TIMESTAMPTZ,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX password_reset_emails_dispatch ON password_reset_emails(available_at, created_at)
    WHERE failed_at IS NULL;
CREATE INDEX password_reset_emails_expiry ON password_reset_emails(expires_at);
CREATE INDEX password_reset_emails_account ON password_reset_emails(email, created_at);
