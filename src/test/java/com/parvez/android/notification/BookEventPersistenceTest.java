package com.parvez.android.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "BOOK_EVENTS_TEST_JDBC_URL", matches = ".+")
class BookEventPersistenceTest {
    private Connection connect() throws Exception {
        return DriverManager.getConnection(System.getenv("BOOK_EVENTS_TEST_JDBC_URL"));
    }

    @Test
    void eventsAreAtomicAndConcurrentWritersCannotOvertakeTheCursor() throws Exception {
        String schema = "notification_test_" + System.nanoTime();
        try (var admin = connect(); var first = connect(); var second = connect()) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                for (var connection : new Connection[]{admin, first, second}) {
                    connection.createStatement().execute("SET search_path TO " + schema);
                }
                for (String migration : new String[]{"V1__create_book_table.sql", "V3__book_created_events.sql"}) {
                    admin.createStatement().execute(Files.readString(Path.of("src/main/resources/db/migration", migration)));
                }
                first.setAutoCommit(false);
                insert(first, "1111111111");
                assertEquals(0, count(admin));
                first.rollback();
                assertEquals(0, count(admin));
                insert(first, "2222222222");
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var later = executor.submit(() -> { insert(second, "3333333333"); return true; });
                    // The second writer must wait until the first event's transaction commits.
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> later.get(200, TimeUnit.MILLISECONDS));
                    first.commit();
                    assertTrue(later.get(5, TimeUnit.SECONDS));
                }
                assertEquals(2, count(admin));
                try (var rows = admin.createStatement().executeQuery(
                        "SELECT id, payload::json->'book'->>'isbn' AS isbn FROM book_events ORDER BY id")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getLong("id"));
                    assertEquals("2222222222", rows.getString("isbn"));
                    assertTrue(rows.next());
                    assertEquals(2, rows.getLong("id"));
                    assertEquals("3333333333", rows.getString("isbn"));
                    assertFalse(rows.next());
                }
                assertThrows(java.sql.SQLException.class, () -> insert(second, "3333333333"));
                admin.createStatement().executeUpdate("UPDATE books SET title = 'changed'");
                assertEquals(2, count(admin));
            } finally {
                first.rollback();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void insert(Connection connection, String isbn) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO books (isbn, title, author, publication_date) VALUES (?, 'Title', 'Author', '2026')")) {
            statement.setString(1, isbn);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection) throws Exception {
        try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM book_events")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
