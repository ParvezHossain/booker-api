package com.parvez.android.mapper;

import com.parvez.android.dto.BookResponse;
import com.parvez.android.model.Book;
import org.springframework.stereotype.Component;


@Component
public class BookMapper {
    public BookResponse toBookResponse(Book book) {
        return new BookResponse(
                book.getId(),
                book.getIsbn(),
                book.getTitle(),
                book.getAuthor(),
                book.getPublishedDate(),
                book.getDescription(),
                book.isCompleted()
        );
    }
}
