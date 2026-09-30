package com.parvez.android.model;

import jakarta.persistence.*;

@Entity
@Table(
        name = "books",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_books_workspace_author_title",
                        columnNames = {"workspace_id", "author", "title"}
                )
        }
)
public class Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_type", nullable = false, length = 10)
    private String libraryType = "PRIVATE";

    public String getLibraryType() { return libraryType; }
    public void setLibraryType(String libraryType) { this.libraryType = libraryType; }

    @Column(name = "workspace_id")
    private java.util.UUID workspaceId;
    public java.util.UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(java.util.UUID workspaceId) { this.workspaceId = workspaceId; }


    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String author;

    @Column(name = "publication_date", nullable = false, length = 20)
    private String publishedDate;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean completed;

    public Book() {
    }

    public Book(String title, String author, String publishedDate, String description) {
        this.title = title;
        this.author = author;
        this.publishedDate = publishedDate;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getPublishedDate() {
        return publishedDate;
    }

    public void setPublishedDate(String publishedDate) {
        this.publishedDate = publishedDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }
}
