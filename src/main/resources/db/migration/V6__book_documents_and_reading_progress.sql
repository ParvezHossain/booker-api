CREATE TABLE book_documents (
    id UUID PRIMARY KEY,
    book_id BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    storage_provider VARCHAR(20) NOT NULL,
    storage_key VARCHAR(100) NOT NULL UNIQUE,
    original_file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL CHECK (mime_type = 'application/pdf'),
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    page_count INTEGER NOT NULL CHECK (page_count > 0),
    checksum VARCHAR(64) NOT NULL,
    source_type VARCHAR(20) NOT NULL CHECK (source_type IN ('UPLOAD', 'GOOGLE_DRIVE')),
    created_by VARCHAR(254) NOT NULL REFERENCES workspace_users(email),
    operation_id UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, book_id),
    UNIQUE (book_id, created_by, operation_id)
);
CREATE UNIQUE INDEX book_documents_one_active ON book_documents(book_id) WHERE active;
CREATE INDEX book_documents_book_created ON book_documents(book_id, created_at);

CREATE TABLE reading_progress (
    user_email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    book_id BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    current_page INTEGER NOT NULL CHECK (current_page >= 1),
    max_page_reached INTEGER NOT NULL CHECK (max_page_reached >= current_page),
    version BIGINT NOT NULL CHECK (version > 0),
    last_read_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_email, book_id),
    FOREIGN KEY (document_id, book_id) REFERENCES book_documents(id, book_id) ON DELETE CASCADE
);
CREATE INDEX reading_progress_book ON reading_progress(book_id);
CREATE INDEX reading_progress_user_last_read ON reading_progress(user_email, last_read_at DESC);

-- Persist retry identities across restarts and other devices' updates.
CREATE TABLE reading_progress_operations (
    user_email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    book_id BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    current_page INTEGER NOT NULL,
    expected_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_email, operation_id),
    FOREIGN KEY (document_id, book_id) REFERENCES book_documents(id, book_id) ON DELETE CASCADE
);
