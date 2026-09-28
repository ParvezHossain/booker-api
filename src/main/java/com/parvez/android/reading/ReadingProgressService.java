package com.parvez.android.reading;

import com.parvez.android.document.*;
import com.parvez.android.saas.WorkspacePrincipal;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@Service
public class ReadingProgressService {
    private final BookAccess access;
    private final BookDocumentRepository documents;
    private final JdbcTemplate jdbc;
    public ReadingProgressService(BookAccess access, BookDocumentRepository documents, JdbcTemplate jdbc) {
        this.access = access; this.documents = documents; this.jdbc = jdbc;
    }
    @Transactional(readOnly = true)
    public ReadingProgress get(long bookId) {
        access.require(bookId);
        return progress(active(bookId));
    }
    private BookDocument active(long bookId) {
        return documents.active(bookId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book has no PDF"));
    }
    private ReadingProgress progress(BookDocument doc) {
        return jdbc.query("SELECT * FROM reading_progress WHERE user_email = ? AND book_id = ? AND document_id = ?",
                (rs, row) -> ReadingProgress.of(doc.bookId(), doc.id(), rs.getInt("current_page"), doc.pageCount(),
                        rs.getInt("max_page_reached"), rs.getTimestamp("last_read_at").toInstant(), rs.getLong("version")),
                WorkspacePrincipal.currentEmail(), doc.bookId(), doc.id()).stream().findFirst()
                .orElseGet(() -> ReadingProgress.of(doc.bookId(), doc.id(), 0, doc.pageCount(), 0, null, 0));
    }
    @Transactional
    public UpdateResult update(long bookId, Update request) {
        access.lock(bookId);
        var doc = active(bookId);
        if (!doc.id().equals(request.documentId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "The PDF was replaced; reopen the book");
        if (request.currentPage() < 1 || request.currentPage() > doc.pageCount())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currentPage must be between 1 and totalPages");
        String email = WorkspacePrincipal.currentEmail();
        var oldOperations = jdbc.queryForList("SELECT * FROM reading_progress_operations WHERE user_email = ? AND operation_id = ?",
                email, request.operationId());
        if (!oldOperations.isEmpty()) {
            var old = oldOperations.getFirst();
            if (((Number) old.get("book_id")).longValue() != bookId || !old.get("document_id").equals(request.documentId())
                    || ((Number) old.get("current_page")).intValue() != request.currentPage()
                    || ((Number) old.get("expected_version")).longValue() != request.version())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID was already used for a different update");
            return new UpdateResult(progress(doc), false);
        }
        var current = progress(doc);
        if (current.version() != request.version()) {
            // Preserve resume position; merging the maximum does not claim a new reading timestamp.
            if (request.version() < current.version() && request.currentPage() > current.pagesRead())
                jdbc.update("UPDATE reading_progress SET max_page_reached = ?, version = version + 1, updated_at = now() WHERE user_email = ? AND book_id = ? AND document_id = ?",
                        request.currentPage(), email, bookId, doc.id());
            return new UpdateResult(progress(doc), true);
        }
        jdbc.update("""
                INSERT INTO reading_progress (user_email, book_id, document_id, current_page, max_page_reached, version, last_read_at)
                VALUES (?, ?, ?, ?, ?, 1, now())
                ON CONFLICT (user_email, book_id) DO UPDATE SET document_id = EXCLUDED.document_id,
                    current_page = EXCLUDED.current_page, max_page_reached = EXCLUDED.max_page_reached,
                    version = CASE WHEN reading_progress.document_id = EXCLUDED.document_id THEN reading_progress.version + 1 ELSE 1 END,
                    last_read_at = now(), updated_at = now()
                """, email, bookId, doc.id(), request.currentPage(), Math.max(current.pagesRead(), request.currentPage()));
        jdbc.update("INSERT INTO reading_progress_operations (user_email, operation_id, book_id, document_id, current_page, expected_version) VALUES (?, ?, ?, ?, ?, ?)",
                email, request.operationId(), bookId, doc.id(), request.currentPage(), request.version());
        return new UpdateResult(progress(doc), false);
    }
    @Transactional(readOnly = true)
    public List<Summary> summaries(List<Long> bookIds) {
        if (bookIds.isEmpty() || bookIds.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply 1–100 book IDs");
        var ids = bookIds.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(WorkspacePrincipal.currentEmail());
        parameters.add(WorkspacePrincipal.currentWorkspace());
        parameters.addAll(ids);
        var result = jdbc.query("""
                SELECT b.id AS book_id, d.id AS document_id, d.original_file_name, d.file_size, d.page_count,
                       d.checksum, d.source_type, d.created_at, p.current_page, p.max_page_reached, p.last_read_at, p.version
                FROM books b LEFT JOIN book_documents d ON d.book_id = b.id AND d.active
                LEFT JOIN reading_progress p ON p.book_id = b.id AND p.document_id = d.id AND p.user_email = ?
                WHERE b.workspace_id = ? AND b.id IN (
                """ + placeholders + ") ORDER BY b.id", (rs, row) -> {
            long id = rs.getLong("book_id");
            UUID document = rs.getObject("document_id", UUID.class);
            if (document == null) return new Summary(id, null, null);
            var metadata = new BookDocument.DocumentResponse(id, document, rs.getString("original_file_name"),
                    rs.getLong("file_size"), "application/pdf", rs.getInt("page_count"), rs.getString("checksum"),
                    rs.getString("source_type"), true, rs.getTimestamp("created_at").toInstant());
            var lastRead = rs.getTimestamp("last_read_at");
            return new Summary(id, metadata, ReadingProgress.of(id, document, rs.getInt("current_page"),
                    rs.getInt("page_count"), rs.getInt("max_page_reached"), lastRead == null ? null : lastRead.toInstant(), rs.getLong("version")));
        }, parameters.toArray());
        if (result.size() != ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found");
        return result;
    }
    public record Update(@NotNull UUID documentId, @Min(1) int currentPage,
                         @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true)
                         @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
                         @Min(0) long version, @NotNull UUID operationId) {}
    public record UpdateResult(ReadingProgress progress, boolean conflict) {}
    public record Summary(long bookId, BookDocument.DocumentResponse document, ReadingProgress progress) {}
}
