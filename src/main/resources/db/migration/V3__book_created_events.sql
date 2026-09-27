-- A transactional counter (rather than a sequence) keeps cursor order equal to
-- commit order. Concurrent inserts wait on this row until the writer commits.
CREATE TABLE book_event_cursor (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    last_id BIGINT NOT NULL
);
INSERT INTO book_event_cursor (last_id) VALUES (0);

CREATE TABLE book_events (
    id BIGINT PRIMARY KEY,
    payload TEXT NOT NULL
);

CREATE FUNCTION record_book_created() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    event_id BIGINT;
BEGIN
    UPDATE book_event_cursor SET last_id = last_id + 1
        WHERE singleton = TRUE RETURNING last_id INTO event_id;
    INSERT INTO book_events (id, payload)
    VALUES (event_id, json_build_object(
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

CREATE TRIGGER books_created_event AFTER INSERT ON books
    FOR EACH ROW EXECUTE FUNCTION record_book_created();
