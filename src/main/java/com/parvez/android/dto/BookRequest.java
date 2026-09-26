package com.parvez.android.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BookRequest(

        @NotBlank(message = "ISBN is required")
        @Size(max = 20, message = "ISBN must not exceed 20 characters")
        @Pattern(
                regexp = "^(?:\\d{10}|\\d{13})$",
                message = "ISBN must contain 10 or 13 digits"
        )
        String isbn,

        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @NotBlank(message = "Author is required")
        @Size(max = 255, message = "Author must not exceed 255 characters")
        String author,

        @NotBlank(message = "Publication date is required")
        @Size(max = 100, message = "Publication date must not exceed 100 characters")
        String publishedDate,

        @Size(max = 5000, message = "Description must not exceed 5000 characters")
        String description,

        boolean completed
) {
}
