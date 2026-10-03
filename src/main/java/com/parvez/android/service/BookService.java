package com.parvez.android.service;

import com.parvez.android.dto.BookRequest;
import com.parvez.android.dto.BookResponse;
import com.parvez.android.exception.BookAlreadyExistsException;
import com.parvez.android.mapper.BookMapper;
import com.parvez.android.model.Book;
import com.parvez.android.repository.BookRepository;
import com.parvez.android.saas.WorkspacePrincipal;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class BookService {
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final JdbcTemplate jdbc;

    public BookService(BookRepository bookRepository, BookMapper bookMapper, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
    }

    private UUID workspace() { return WorkspacePrincipal.currentWorkspace(); }

    public List<BookResponse> getBooks() {
        return bookRepository.findAllByWorkspaceId(workspace())
                .stream()
                .map(bookMapper::toBookResponse)
                .toList();
    }

    @Transactional
    public BookResponse createBook(@Valid BookRequest request) {
        // Serialize quota checks so concurrent creates cannot both consume the final slot.
        int limit = jdbc.queryForObject("SELECT book_limit FROM workspaces WHERE id = ? FOR UPDATE", Integer.class, workspace());
        if (bookRepository.existsByWorkspaceIdAndAuthorAndTitle(workspace(), request.author(), request.title())) {
            throw new BookAlreadyExistsException("A book with this author and title already exists in your workspace");
        }
        if (bookRepository.countByWorkspaceId(workspace()) >= limit) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace book limit reached");
        }
        Book book = new Book();
        book.setWorkspaceId(workspace());
        bookMapper.applyMetadata(book, request);

        Book savedBook = bookRepository.save(book);
        return bookMapper.toBookResponse(savedBook);
    }

    public BookResponse getBookById(long bookId) {
        return bookRepository.findByIdAndWorkspaceId(bookId, workspace()).map(bookMapper::toBookResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found"));
    }

    public List<BookResponse> getBooksByAuthor(String author) {
        return bookRepository.findAllByWorkspaceIdAndAuthor(workspace(), author).stream().map(bookMapper::toBookResponse).toList();
    }

    public List<BookResponse> getBooksByTitle(String title) {
        return bookRepository.findAllByWorkspaceIdAndTitle(workspace(), title).stream().map(bookMapper::toBookResponse).toList();
    }

    public List<BookResponse> getBooksByAuthorAndTitle(String author, String title) {
        return bookRepository.findAllByWorkspaceIdAndAuthorAndTitle(workspace(), author, title)
                .stream()
                .map(bookMapper::toBookResponse)
                .toList();
    }
}
