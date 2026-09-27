CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    plan VARCHAR(20) NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'PRO')),
    book_limit INTEGER NOT NULL DEFAULT 100 CHECK (book_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE workspace_users (
    email VARCHAR(254) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Preserve old records without granting new signups access to them.
INSERT INTO workspaces (id, name, book_limit)
VALUES ('00000000-0000-0000-0000-000000000001', 'Legacy catalogue', 1000000);
ALTER TABLE books ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
UPDATE books SET workspace_id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE books ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE books DROP CONSTRAINT uk_books_isbn;
ALTER TABLE books ADD CONSTRAINT uk_books_workspace_isbn UNIQUE (workspace_id, isbn);
ALTER TABLE book_events ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
UPDATE book_events SET workspace_id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE book_events ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX book_events_workspace_cursor ON book_events(workspace_id, id);
CREATE OR REPLACE FUNCTION record_book_created() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    event_id BIGINT;
BEGIN
    UPDATE book_event_cursor SET last_id = last_id + 1
        WHERE singleton = TRUE RETURNING last_id INTO event_id;
    INSERT INTO book_events (id, workspace_id, payload)
    VALUES (event_id, NEW.workspace_id, json_build_object(
        'eventId', event_id::TEXT,
        'type', 'book.created',
        'schemaVersion', 1,
        'occurredAt', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'book', json_build_object(
            'id', NEW.id, 'isbn', NEW.isbn, 'title', NEW.title,
            'author', NEW.author, 'publishedDate', NEW.publication_date,
            'description', NEW.description, 'completed', NEW.completed
        )
    )::TEXT);
    RETURN NEW;
END;
$$;

