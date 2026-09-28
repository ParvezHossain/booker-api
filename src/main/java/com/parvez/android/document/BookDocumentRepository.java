package com.parvez.android.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BookDocumentRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<BookDocument> MAPPER = (rs, row) -> new BookDocument(
            rs.getObject("id", UUID.class), rs.getLong("book_id"), rs.getString("storage_provider"),
            rs.getString("storage_key"), rs.getString("original_file_name"), rs.getLong("file_size"),
            rs.getInt("page_count"), rs.getString("checksum"), rs.getString("source_type"),
            rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());
    public BookDocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<BookDocument> active(long bookId) {
        return jdbc.query("SELECT * FROM book_documents WHERE book_id = ? AND active", MAPPER, bookId).stream().findFirst();
    }
    public Optional<BookDocument> operation(long bookId, String email, UUID operation) {
        return jdbc.query("SELECT * FROM book_documents WHERE book_id = ? AND created_by = ? AND operation_id = ?",
                MAPPER, bookId, email, operation).stream().findFirst();
    }
    public void activate(BookDocument doc, String email, UUID operation) {
        jdbc.update("UPDATE book_documents SET active = FALSE, updated_at = now() WHERE book_id = ? AND active", doc.bookId());
        jdbc.update("""
                INSERT INTO book_documents (id, book_id, storage_provider, storage_key, original_file_name,
                    mime_type, file_size, page_count, checksum, source_type, created_by, operation_id)
                VALUES (?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?, ?, ?, ?)
                """, doc.id(), doc.bookId(), doc.storageProvider(), doc.storageKey(), doc.fileName(), doc.fileSize(),
                doc.pageCount(), doc.checksum(), doc.sourceType(), email, operation);
    }
}
