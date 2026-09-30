package com.parvez.android.repository;
import com.parvez.android.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface BookRepository extends JpaRepository<Book, Long> {
    List<Book> findAllByWorkspaceId(UUID workspaceId);
    List<Book> findAllByWorkspaceIdAndAuthor(UUID workspaceId, String author);
    List<Book> findAllByWorkspaceIdAndTitle(UUID workspaceId, String title);
    List<Book> findAllByWorkspaceIdAndAuthorAndTitle(UUID workspaceId, String author, String title);
    boolean existsByWorkspaceIdAndAuthorAndTitle(UUID workspaceId, String author, String title);
    Optional<Book> findByIdAndWorkspaceId(Long id, UUID workspaceId);
    long countByWorkspaceId(UUID workspaceId);
}
