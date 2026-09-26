package com.parvez.android.controller;

import com.parvez.android.dto.BookRequest;
import com.parvez.android.dto.BookResponse;
import com.parvez.android.service.BookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/books")
@Validated
@Tag(name = "Book Management", description = "Operations for searching, retrieving, and registering book entries")
@SecurityRequirement(name = "basicAuth")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @Operation(
            summary = "Fetch or search books",
            description = "Retrieves a list of all books or filters them dynamically using author and/or title parameters."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of books",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = BookResponse.class))
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Basic authentication required", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping
    public ResponseEntity<List<BookResponse>> getBooks(
            @Parameter(description = "Author's full name to filter by", example = "Joshua Bloch")
            @RequestParam(required = false) String author,

            @Parameter(description = "Book title to filter by", example = "Effective Java")
            @RequestParam(required = false) String title) {

        if (author != null && title != null) {
            return ResponseEntity.ok(bookService.getBooksByAuthorAndTitle(author, title));
        } else if (author != null) {
            return ResponseEntity.ok(bookService.getBooksByAuthor(author));
        } else if (title != null) {
            return ResponseEntity.ok(bookService.getBooksByTitle(title));
        }

        return ResponseEntity.ok(bookService.getBooks());
    }

    @Operation(
            summary = "Get book by ISBN",
            description = "Retrieves details of a single book using its unique 10 or 13-digit ISBN string."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Book found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookResponse.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Basic authentication required", content = @Content),
            @ApiResponse(responseCode = "404", description = "No book found matching the given ISBN", content = @Content)
    })
    @GetMapping("/isbn/{isbn}")
    public ResponseEntity<BookResponse> getBookByIsbn(
            @Parameter(
                    description = "10 or 13-digit ISBN number",
                    example = "9780134685991",
                    required = true
            )
            @PathVariable String isbn) {
        return ResponseEntity.ok(bookService.getBookByISBN(isbn));
    }

    @Operation(
            summary = "Create a new book record",
            description = "Validates the request payload and saves a new book. Returns the created book details and sets the Location header."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Book created successfully",
                    headers = @Header(
                            name = "Location",
                            description = "URI of the newly created book resource",
                            schema = @Schema(type = "string", example = "/api/books/1")
                    ),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Bad Request - Request body failed validation constraints", content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Basic authentication required", content = @Content),
            @ApiResponse(responseCode = "409", description = "Conflict - Book with duplicate ISBN or title already exists", content = @Content)
    })
    @PostMapping
    public ResponseEntity<BookResponse> createBook(
            @Parameter(description = "Book details matching validation rules", required = true)
            @Valid @RequestBody BookRequest bookRequest) {

        BookResponse response = bookService.createBook(bookRequest);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity
                .created(location)
                .body(response);
    }
}