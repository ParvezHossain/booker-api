-- Do not merge/delete books: documents and progress depend on their stable IDs.
-- A populated installation must resolve duplicate exact pairs before upgrading.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM books GROUP BY workspace_id, author, title HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Duplicate author/title pairs exist in a workspace; resolve them before applying V9';
    END IF;
END;
$$;

ALTER TABLE books ADD CONSTRAINT uk_books_workspace_author_title UNIQUE (workspace_id, author, title);

-- Replace the trigger before removing the column it previously referenced.
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
        'schemaVersion', 2,
        'occurredAt', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'book', json_build_object(
            'id', NEW.id, 'title', NEW.title,
            'author', NEW.author, 'publishedDate', NEW.publication_date,
            'description', NEW.description, 'completed', NEW.completed
        )
    )::TEXT);
    RETURN NEW;
END;
$$;

-- Keep replay event IDs, order and snapshot values; upgrade only the payload contract.
UPDATE book_events SET payload = jsonb_set(
    payload::jsonb #- '{book,isbn}', '{schemaVersion}', '2'::jsonb
)::text;

ALTER TABLE books DROP CONSTRAINT uk_books_workspace_isbn;
ALTER TABLE books DROP COLUMN isbn;
