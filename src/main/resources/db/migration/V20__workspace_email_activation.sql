-- Preserve existing account access; public signup explicitly inserts pending owners.
ALTER TABLE workspace_users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE workspace_users ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE workspace_users ADD CONSTRAINT workspace_users_email_verification_time
    CHECK (email_verified OR email_verified_at IS NULL);

CREATE TABLE email_activation_tokens (
    email VARCHAR(254) PRIMARY KEY REFERENCES workspace_users(email) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX email_activation_tokens_expiry ON email_activation_tokens(expires_at);

-- Only opaque receipt IDs go to RabbitMQ; token payloads are AES-GCM protected.
CREATE TABLE email_activation_emails (
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
CREATE INDEX email_activation_emails_dispatch ON email_activation_emails(available_at, created_at)
    WHERE failed_at IS NULL;
CREATE INDEX email_activation_emails_expiry ON email_activation_emails(expires_at);
CREATE INDEX email_activation_emails_account ON email_activation_emails(email, created_at);
