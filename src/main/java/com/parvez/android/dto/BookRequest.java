package com.parvez.android.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload required to create or update a book entry")
public record BookRequest(

        @Schema(
                description = "10 or 13-digit International Standard Book Number",
                example = "9780134685991",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "ISBN is required")
        @Size(max = 20, message = "ISBN must not exceed 20 characters")
        @Pattern(
                regexp = "^(?:\\d{10}|\\d{13})$",
                message = "ISBN must contain 10 or 13 digits"
        )
        String isbn,

        @Schema(
                description = "Title of the book",
                example = "Effective Java",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @Schema(
                description = "Author's full name",
                example = "Joshua Bloch",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Author is required")
        @Size(max = 255, message = "Author must not exceed 255 characters")
        String author,

        @Schema(
                description = "Publication date or year string",
                example = "2018-01-11",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Publication date is required")
        @Size(max = 100, message = "Publication date must not exceed 100 characters")
        String publishedDate,

        @Schema(
                description = "Detailed summary or overview of the book",
                example = "A comprehensive guide to Java programming best practices."
        )
        @Size(max = 5000, message = "Description must not exceed 5000 characters")
        String description,

        @Schema(
                description = "Status indicating whether the reading has been completed",
                example = "true"
        )
        boolean completed
) {
}