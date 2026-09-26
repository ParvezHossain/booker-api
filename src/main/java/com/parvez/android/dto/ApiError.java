package com.parvez.android.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ApiError(
        LocalDateTime dateTime,
        int status,
        String error,
        String message,
        String path
) {
}
