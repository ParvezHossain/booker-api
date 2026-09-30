package com.parvez.android.service;

import com.parvez.android.dto.BookRequest;
import com.parvez.android.dto.BookResponse;
import com.parvez.android.exception.BookAlreadyExistsException;
import com.parvez.android.mapper.BookMapper;
import com.parvez.android.model.Book;
import com.parvez.android.repository.BookRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class BookService {
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public BookService(BookRepository bookRepository, BookMapper bookMapper, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
    }

    private java.util.UUID workspace() { return com.parvez.android.saas.WorkspacePrincipal.currentWorkspace(); }

    public List<BookResponse> getBooks() {
        return bookRepository.findAllByWorkspaceId(workspace())
                .stream()
                .map(bookMapper::toBookResponse)
                .toList();
    }

    @Transactional
    public BookResponse createBook(@Valid BookRequest request) {
        int limit = jdbc.queryForObject("SELECT book_limit FROM workspaces WHERE id = ? FOR UPDATE", Integer.class, workspace());
        if (bookRepository.existsByWorkspaceIdAndAuthorAndTitle(workspace(), request.author(), request.title())) {
            throw new BookAlreadyExistsException("A book with this author and title already exists in your workspace");
        }
        if (bookRepository.countByWorkspaceId(workspace()) >= limit) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Workspace book limit reached");
        }
        Book book = new Book();
        book.setWorkspaceId(workspace());
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setPublishedDate(request.publishedDate());
        book.setDescription(request.description());
        book.setCompleted(request.completed());

        Book savedBook = bookRepository.save(book);
        return bookMapper.toBookResponse(savedBook);
    }

    public BookResponse getBookById(long bookId) {
        return bookRepository.findByIdAndWorkspaceId(bookId, workspace()).map(bookMapper::toBookResponse)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Book not found"));
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
