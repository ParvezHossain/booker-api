ALTER TABLE workspace_users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'OWNER'
    CHECK (role IN ('OWNER', 'SUPER_ADMIN'));
ALTER TABLE workspace_users ALTER COLUMN workspace_id DROP NOT NULL;
ALTER TABLE workspace_users ADD CONSTRAINT workspace_users_role_scope CHECK (
    (role = 'OWNER' AND workspace_id IS NOT NULL) OR
    (role = 'SUPER_ADMIN' AND workspace_id IS NULL)
);

ALTER TABLE books ADD COLUMN library_type VARCHAR(10) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE books ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE books ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE books ALTER COLUMN workspace_id DROP NOT NULL;
ALTER TABLE books ADD CONSTRAINT books_library_scope CHECK (
    (library_type = 'PRIVATE' AND workspace_id IS NOT NULL) OR
    (library_type = 'PUBLIC' AND workspace_id IS NULL)
);
CREATE UNIQUE INDEX uk_books_public_author_title ON books(author, title) WHERE library_type = 'PUBLIC';
CREATE INDEX books_public_id ON books(id) WHERE library_type = 'PUBLIC';

-- Public creation does not enter the private, workspace-scoped notification log.
CREATE OR REPLACE FUNCTION record_book_created() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    event_id BIGINT;
BEGIN
    IF NEW.library_type = 'PUBLIC' THEN RETURN NEW; END IF;
    UPDATE book_event_cursor SET last_id = last_id + 1
        WHERE singleton = TRUE RETURNING last_id INTO event_id;
    INSERT INTO book_events (id, workspace_id, payload)
    VALUES (event_id, NEW.workspace_id, json_build_object(
        'eventId', event_id::TEXT,
        'type', 'book.created',
        'schemaVersion', 2,
        'occurredAt', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'book', json_build_object(
            'id', NEW.id, 'title', NEW.title, 'author', NEW.author,
            'publishedDate', NEW.publication_date, 'description', NEW.description,
            'completed', NEW.completed
        )
    )::TEXT);
    RETURN NEW;
END;
$$;

CREATE TABLE public_reading_progress (
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    book_id BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    current_page INTEGER NOT NULL CHECK (current_page >= 1),
    max_page_reached INTEGER NOT NULL CHECK (max_page_reached >= current_page),
    version BIGINT NOT NULL CHECK (version > 0),
    last_read_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, book_id),
    FOREIGN KEY (document_id, book_id) REFERENCES book_documents(id, book_id) ON DELETE CASCADE
);
CREATE INDEX public_reading_progress_book ON public_reading_progress(book_id);
CREATE INDEX public_reading_progress_document_book ON public_reading_progress(document_id, book_id);
CREATE INDEX public_reading_progress_workspace_last_read ON public_reading_progress(workspace_id, last_read_at DESC);

CREATE TABLE public_reading_progress_operations (
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    book_id BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    current_page INTEGER NOT NULL,
    expected_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, operation_id),
    FOREIGN KEY (document_id, book_id) REFERENCES book_documents(id, book_id) ON DELETE CASCADE
);
CREATE INDEX public_reading_progress_operations_book_document ON public_reading_progress_operations(book_id, document_id);

-- Durable cleanup receipts outlive book/document deletion; no FK to removed metadata.
CREATE TABLE document_file_deletions (
    storage_key VARCHAR(100) PRIMARY KEY,
    storage_provider VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keep stored public page counts valid, even for direct SQL writes.
CREATE FUNCTION validate_public_reading_progress() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    total_pages INTEGER;
BEGIN
    SELECT d.page_count INTO total_pages FROM book_documents d JOIN books b ON b.id = d.book_id
        WHERE d.id = NEW.document_id AND d.book_id = NEW.book_id AND b.library_type = 'PUBLIC';
    IF total_pages IS NULL OR NEW.current_page < 1 OR NEW.current_page > total_pages
        OR NEW.max_page_reached > total_pages THEN
        RAISE EXCEPTION 'Public reading progress must reference a public PDF and valid pages' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validate_public_reading_progress BEFORE INSERT OR UPDATE ON public_reading_progress
    FOR EACH ROW EXECUTE FUNCTION validate_public_reading_progress();
