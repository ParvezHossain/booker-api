package com.parvez.android.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class BookEventStore {
    private final JdbcTemplate jdbc;

    public BookEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long latestId() {
        return jdbc.queryForObject("SELECT last_id FROM book_event_cursor WHERE singleton = TRUE", Long.class);
    }

    public List<Event> after(long cursor, java.util.UUID workspaceId) {
        return jdbc.query("SELECT id, payload FROM book_events WHERE id > ? AND workspace_id = ? ORDER BY id LIMIT 100",
                (rs, row) -> new Event(rs.getLong("id"), rs.getString("payload")), cursor, workspaceId);
    }

    public record Event(long id, String payload) {}
}
