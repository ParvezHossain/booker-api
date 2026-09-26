package com.parvez.android.dto;

public record BookResponse(
        Long id,
        String isbn,
        String title,
        String author,
        String publishedDate,
        String description,
        boolean completed) {
}
