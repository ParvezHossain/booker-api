package com.parvez.android.document;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BookReadingMigrationTest {
    @Autowired DataSource dataSource;

    @Test void upgradePreservesExistingBooksAndEnforcesReadingConstraints() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "5");
            var jdbc = schema.jdbc();
            var books = jdbc.queryForList("SELECT * FROM books ORDER BY id");
            assertFalse(books.isEmpty());
            var events = jdbc.queryForList("SELECT * FROM book_events ORDER BY id");

            migrate(schema.name(), null);
            assertEquals(books, jdbc.queryForList("SELECT * FROM books ORDER BY id"));
            assertEquals(events, jdbc.queryForList("SELECT * FROM book_events ORDER BY id"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_documents", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress", Integer.class));

            long book = ((Number) books.getFirst().get("id")).longValue();
            long otherBook = ((Number) books.get(1).get("id")).longValue();
            UUID document = seedProgress(jdbc, book);
            assertThrows(DataIntegrityViolationException.class, () -> insertDocument(jdbc, book, UUID.randomUUID(), "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertDocument(jdbc, Long.MAX_VALUE, UUID.randomUUID(), "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertDocument(jdbc, otherBook, UUID.randomUUID(), "missing@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertProgress(jdbc, book, document, "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertProgress(jdbc, otherBook, document, "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertProgress(jdbc, book, document, "missing@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE reading_progress SET current_page = 0 WHERE book_id = ?", book));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE reading_progress SET max_page_reached = 1 WHERE book_id = ?", book));

            jdbc.update("INSERT INTO reading_progress_operations (user_email, operation_id, book_id, document_id, current_page, expected_version) VALUES (?, ?, ?, ?, 93, 0)",
                    "reader@example.com", UUID.randomUUID(), book, document);
            jdbc.update("DELETE FROM book_documents WHERE id = ?", document);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress_operations", Integer.class));
            assertEquals(books, jdbc.queryForList("SELECT * FROM books ORDER BY id"));
        });
    }

    @Test void referenceIndexesPreservePopulatedReadingData() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "7");
            var jdbc = schema.jdbc();
            long book = jdbc.queryForObject("SELECT min(id) FROM books", Long.class);
            UUID document = seedProgress(jdbc, book);
            jdbc.update("INSERT INTO reading_progress_operations (user_email, operation_id, book_id, document_id, current_page, expected_version) VALUES (?, ?, ?, ?, 93, 0)",
                    "reader@example.com", UUID.randomUUID(), book, document);
            var documents = jdbc.queryForList("SELECT * FROM book_documents");
            var progress = jdbc.queryForList("SELECT * FROM reading_progress");
            var operations = jdbc.queryForList("SELECT * FROM reading_progress_operations");

            migrate(schema.name(), null);
            assertEquals(documents, jdbc.queryForList("SELECT * FROM book_documents"));
            assertEquals(progress, jdbc.queryForList("SELECT * FROM reading_progress"));
            assertEquals(operations, jdbc.queryForList("SELECT * FROM reading_progress_operations"));
            assertReferenceIndexes(jdbc, schema.name());
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void freshInstallAppliesAndValidatesAllMigrations() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), null);
            assertReferenceIndexes(schema.jdbc(), schema.name());
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    private UUID seedProgress(JdbcTemplate jdbc, long book) {
        jdbc.update("INSERT INTO workspace_users (email, password_hash, workspace_id) SELECT 'reader@example.com', 'test-only', workspace_id FROM books WHERE id = ?", book);
        UUID document = UUID.randomUUID();
        insertDocument(jdbc, book, document, "reader@example.com");
        insertProgress(jdbc, book, document, "reader@example.com");
        return document;
    }

    private void insertDocument(JdbcTemplate jdbc, long book, UUID document, String user) {
        jdbc.update("""
                INSERT INTO book_documents (id, book_id, storage_provider, storage_key, original_file_name,
                    mime_type, file_size, page_count, checksum, source_type, created_by, operation_id)
                VALUES (?, ?, 'LOCAL', ?, 'book.pdf', 'application/pdf', 1024, 144, ?, 'UPLOAD', ?, ?)
                """, document, book, document + ".pdf", "a".repeat(64), user, UUID.randomUUID());
    }

    private void insertProgress(JdbcTemplate jdbc, long book, UUID document, String user) {
        jdbc.update("""
                INSERT INTO reading_progress (user_email, book_id, document_id, current_page,
                    max_page_reached, version, last_read_at) VALUES (?, ?, ?, 93, 93, 1, now())
                """, user, book, document);
    }

    private void assertReferenceIndexes(JdbcTemplate jdbc, String schema) {
        var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, schema);
        assertTrue(indexes.containsAll(java.util.List.of("book_documents_creator",
                "reading_progress_document_book", "reading_progress_operations_book_document")));
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private void migrate(String schema, String target) {
        var flyway = flyway(schema, target);
        flyway.migrate();
        flyway.validate();
    }

    private void inSchema(Consumer<TestSchema> test) throws Exception {
        String schema = "reading_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String previousSchema = connection.getSchema();
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                test.accept(new TestSchema(schema, jdbc));
            } finally {
                connection.setSchema(previousSchema);
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private record TestSchema(String name, JdbcTemplate jdbc) {}
}
