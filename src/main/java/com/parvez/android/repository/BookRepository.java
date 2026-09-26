package com.parvez.android.repository;

import com.parvez.android.dto.BookResponse;
import com.parvez.android.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookRepository extends JpaRepository<Book, Long> {
    List<BookResponse> findAllByAuthor(String author);
    BookResponse findAllByTitle(String title);
    List<BookResponse> findAllByAuthorAndTitle(String author, String title);
    BookResponse findAllByTitleAndAuthor(String title, String author);
    List<BookResponse> findByAuthor(String author);
    BookResponse findByTitle(String title);
    BookResponse findByIsbn(String isbn);
    boolean existsByIsbn(String isbn);
    boolean existsByTitle(String title);
    boolean existsByIsbnAndIdNot(String isbn, Long id);
}
