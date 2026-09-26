package com.parvez.android.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload representing book details")
public record BookResponse(

        @Schema(description = "Unique database identifier of the book", example = "1")
        Long id,

        @Schema(description = "10 or 13-digit ISBN number", example = "9780134685991")
        String isbn,

        @Schema(description = "Title of the book", example = "Effective Java")
        String title,

        @Schema(description = "Author's full name", example = "Joshua Bloch")
        String author,

        @Schema(description = "Publication date string", example = "2018-01-11")
        String publishedDate,

        @Schema(description = "Detailed description of the book", example = "A comprehensive guide to Java programming best practices.")
        String description,

        @Schema(description = "Reading status of the book", example = "true")
        boolean completed
) {
}