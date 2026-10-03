package com.parvez.android.mapper;

import com.parvez.android.dto.BookResponse;
import com.parvez.android.dto.BookRequest;
import com.parvez.android.model.Book;
import org.springframework.stereotype.Component;


@Component
public class BookMapper {
    /** Copies only editable metadata; ownership and library scope remain service-controlled. */
    public void applyMetadata(Book book, BookRequest request) {
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setPublishedDate(request.publishedDate());
        book.setDescription(request.description());
        book.setCompleted(request.completed());
    }

    public BookResponse toBookResponse(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getPublishedDate(),
                book.getDescription(),
                book.isCompleted()
        );
    }
}
