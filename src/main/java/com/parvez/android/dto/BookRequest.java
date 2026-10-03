package com.parvez.android.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Editable metadata for private and public books")
public record BookRequest(

        @Schema(
                description = "Title of the book; the exact author/title pair must be unique within its private workspace or globally in the public library",
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
        @Size(max = 20, message = "Publication date must not exceed 20 characters")
        String publishedDate,

        @Schema(
                description = "Detailed summary or overview of the book",
                example = "A comprehensive guide to Java programming best practices."
        )
        @Size(max = 5000, message = "Description must not exceed 5000 characters")
        String description,

        @Schema(
                description = "Manually supplied book status. Independent of personal or workspace PDF reading completion.",
                example = "true"
        )
        boolean completed
) {
}
