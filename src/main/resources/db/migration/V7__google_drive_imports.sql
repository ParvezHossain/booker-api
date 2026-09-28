CREATE TABLE google_drive_connections (
    user_email VARCHAR(254) PRIMARY KEY REFERENCES workspace_users(email) ON DELETE CASCADE,
    refresh_token_encrypted TEXT NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE google_drive_oauth_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    binding_hash VARCHAR(64) NOT NULL,
    user_email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    verifier_encrypted TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX google_drive_oauth_expiry ON google_drive_oauth_states(expires_at);
CREATE TABLE google_drive_imports (
    id UUID PRIMARY KEY,
    book_id BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    user_email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    file_id VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    document_id UUID REFERENCES book_documents(id),
    attempts INTEGER NOT NULL DEFAULT 0,
    message VARCHAR(255),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX google_drive_import_queue ON google_drive_imports(status, available_at);
CREATE INDEX google_drive_import_owner ON google_drive_imports(user_email, book_id);
