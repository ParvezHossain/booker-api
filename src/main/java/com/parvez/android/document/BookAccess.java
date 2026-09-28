package com.parvez.android.document;

import com.parvez.android.repository.BookRepository;
import com.parvez.android.saas.WorkspacePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookAccess {
    private final BookRepository books;
    private final JdbcTemplate jdbc;
    public BookAccess(BookRepository books, JdbcTemplate jdbc) { this.books = books; this.jdbc = jdbc; }
    public void require(long bookId) {
        books.findByIdAndWorkspaceId(bookId, WorkspacePrincipal.currentWorkspace())
                .orElseThrow(BookAccess::notFound);
    }
    /** Called inside a transaction. Serializes document replacement and progress updates. */
    public void lock(long bookId) {
        if (jdbc.queryForList("SELECT id FROM books WHERE id = ? AND workspace_id = ? FOR UPDATE",
                bookId, WorkspacePrincipal.currentWorkspace()).isEmpty()) throw notFound();
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found"); }
}
