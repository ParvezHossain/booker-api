package com.parvez.android.library;

import com.parvez.android.document.BookAccess;
import com.parvez.android.dto.BookRequest;
import com.parvez.android.dto.BookResponse;
import com.parvez.android.mapper.BookMapper;
import com.parvez.android.model.Book;
import com.parvez.android.repository.BookRepository;
import com.parvez.android.saas.WorkspacePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class PublicBookService {
    private final BookRepository books;
    private final BookMapper mapper;
    private final BookAccess access;
    private final JdbcTemplate jdbc;
    public PublicBookService(BookRepository books, BookMapper mapper, BookAccess access, JdbcTemplate jdbc) {
        this.books = books; this.mapper = mapper; this.access = access; this.jdbc = jdbc;
    }
    public List<BookResponse> list(String author, String title) {
        WorkspacePrincipal.current();
        List<Book> result;
        if (author != null && title != null) result = books.findAllByLibraryTypeAndAuthorAndTitle("PUBLIC", author, title);
        else if (author != null) result = books.findAllByLibraryTypeAndAuthor("PUBLIC", author);
        else if (title != null) result = books.findAllByLibraryTypeAndTitle("PUBLIC", title);
        else result = books.findAllByLibraryType("PUBLIC");
        return result.stream().map(mapper::toBookResponse).toList();
    }
    public BookResponse get(long bookId) {
        access.requirePublic(bookId);
        return mapper.toBookResponse(book(bookId));
    }
    @Transactional
    public BookResponse create(BookRequest request) {
        WorkspacePrincipal.requireSuperAdmin();
        Book book = new Book();
        book.setLibraryType("PUBLIC");
        metadata(book, request);
        return mapper.toBookResponse(books.saveAndFlush(book));
    }
    @Transactional
    public BookResponse update(long bookId, BookRequest request) {
        WorkspacePrincipal.requireSuperAdmin();
        access.lockPublic(bookId);
        Book book = book(bookId);
        metadata(book, request);
        var result = mapper.toBookResponse(books.saveAndFlush(book));
        jdbc.update("UPDATE books SET updated_at = now() WHERE id = ?", bookId);
        return result;
    }
    @Transactional
    public void delete(long bookId) {
        WorkspacePrincipal.requireSuperAdmin();
        access.lockPublic(bookId);
        // Queue all retained versions before cascading metadata deletion, in the same transaction.
        jdbc.update("""
                INSERT INTO document_file_deletions(storage_key, storage_provider)
                SELECT storage_key, storage_provider FROM book_documents WHERE book_id = ?
                ON CONFLICT (storage_key) DO NOTHING
                """, bookId);
        jdbc.update("DELETE FROM books WHERE id = ? AND library_type = 'PUBLIC'", bookId);
    }
    private Book book(long id) {
        return books.findByIdAndLibraryType(id, "PUBLIC")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Public book not found"));
    }
    private void metadata(Book book, BookRequest request) {
        book.setTitle(request.title()); book.setAuthor(request.author());
        book.setPublishedDate(request.publishedDate()); book.setDescription(request.description());
        book.setCompleted(request.completed());
    }
}
