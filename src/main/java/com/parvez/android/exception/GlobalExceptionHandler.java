package com.parvez.android.exception;

import com.parvez.android.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BookAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleBookAlreadyExists(
            BookAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return buildError(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {

        log.error(
                "Database constraint violation on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        String message = "The requested operation violates a database constraint";

        if (isDuplicateBook(exception)) {
            message = "A book with this author and title already exists in your workspace";
        }

        return buildError(
                HttpStatus.CONFLICT,
                message,
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {

        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error ->
                        error.getField() + ": " + error.getDefaultMessage()
                )
                .collect(Collectors.joining(", "));

        return buildError(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {

        return buildError(
                HttpStatus.BAD_REQUEST,
                "Invalid request body",
                request
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception, HttpServletRequest request) {
        return buildError(HttpStatus.valueOf(exception.getStatusCode().value()), exception.getReason(), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(HttpServletRequest request) {
        return buildError(HttpStatus.PAYLOAD_TOO_LARGE, "PDF exceeds the upload limit", request);
    }

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> handleInvalidParameter(HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "Missing or invalid request parameter", request);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMedia(HttpServletRequest request) {
        return buildError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(
            Exception exception,
            HttpServletRequest request
    ) {

        // IMPORTANT:
        // Log the actual exception. Do not expose it to the client.
        log.error(
                "Unhandled exception on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    private ResponseEntity<ApiError> buildError(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }

    private boolean isDuplicateBook(
            DataIntegrityViolationException exception
    ) {

        Throwable cause = exception;

        while (cause != null) {

            String message = cause.getMessage();

            if (message != null &&
                    message.toLowerCase()
                            .contains("uk_books_workspace_author_title")) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}
