package com.parvez.android.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;


class BookEventStreamTest {
    @Test
    void newConnectionsStartAtLatestAndReconnectsKeepTheirCursor() {
        assertEquals(42, BookEventStream.parseCursor(null, 42));
        assertEquals(0, BookEventStream.parseCursor("0", 42));
        assertEquals(12, BookEventStream.parseCursor("12", 42));
    }

    @Test
    void rejectsInvalidAndFutureCursors() {
        for (String cursor : new String[]{"", "abc", "-1", "43", "9223372036854775808"}) {
            var error = assertThrows(ResponseStatusException.class, () -> BookEventStream.parseCursor(cursor, 42));
            assertEquals(400, error.getStatusCode().value());
        }
    }

    @Test
    void limitsConcurrentConnections() {
        var store = new BookEventStore(null) {
            @Override public long latestId() { return 0; }
            @Override public java.util.List<Event> after(long cursor) { return java.util.List.of(); }
        };
        var stream = new BookEventStream(store, 1, 1000);
        try {
            stream.subscribe(null);
            var error = assertThrows(ResponseStatusException.class, () -> stream.subscribe(null));
            assertEquals(503, error.getStatusCode().value());
        } finally {
            stream.shutdown();
        }
    }
}
