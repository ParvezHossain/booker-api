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
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class BookService {
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;

    public BookService(BookRepository bookRepository, BookMapper bookMapper) {
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
    }

    public List<BookResponse> getBooks() {
        return bookRepository.findAll()
                .stream()
                .map(bookMapper::toBookResponse)
                .toList();
    }

    @Transactional
    public BookResponse createBook(@Valid BookRequest request) {
        if (bookRepository.existsByIsbn(request.isbn())) {
            throw new BookAlreadyExistsException("Book already exists with isbn: " + request.isbn());
        }
        Book book = new Book();
        book.setIsbn(request.isbn());
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setPublishedDate(request.publishedDate());
        book.setDescription(request.description());
        book.setCompleted(request.completed());

        Book savedBook = bookRepository.save(book);
        return bookMapper.toBookResponse(savedBook);
    }

    public BookResponse getBookByISBN(@Valid String isbn) {
        return bookRepository.findByIsbn(isbn);
    }

    public List<BookResponse> getBooksByAuthor(@Valid String author){
        return bookRepository.findAllByAuthor(author)
                .stream()
                .map(bookMapper::toBookResponse)
                .toList();
    }
}
