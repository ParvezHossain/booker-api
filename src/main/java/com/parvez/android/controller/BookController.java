package com.parvez.android.controller;

import com.parvez.android.dto.BookRequest;
import com.parvez.android.dto.ApiError;
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
@Tag(name = "Book Management", description = "Create and search books in your authenticated workspace")
@SecurityRequirement(name = "basicAuth")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @Operation(
            summary = "Fetch or search books",
            description = "Returns books only from your workspace. Optional author and title filters use exact matches; supplying both requires both to match. Results are unpaginated. An empty result is an empty array."
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
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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
            description = "Retrieves a book by ISBN within your workspace. An ISBN belonging only to another workspace returns 404."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Book found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookResponse.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Basic authentication required", content = @Content),
            @ApiResponse(responseCode = "404", description = "No book found matching the given ISBN", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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
            description = "Creates a book in your workspace and records a book.created notification in the same transaction. ISBN must be unique within your workspace; other workspaces may use the same ISBN. FREE workspaces allow 100 books. Returns the created book and its ISBN lookup URL in Location."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Book created successfully",
                    headers = @Header(
                            name = "Location",
                            description = "URI of the newly created book resource",
                            schema = @Schema(type = "string", example = "/api/books/isbn/9780134685991")
                    ),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Bad Request - Request body failed validation constraints", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Basic authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Workspace book limit reached", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - ISBN already exists in your workspace", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<BookResponse> createBook(
            @Parameter(description = "Book details matching validation rules", required = true)
            @Valid @RequestBody BookRequest bookRequest) {

        BookResponse response = bookService.createBook(bookRequest);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/isbn/{isbn}")
                .buildAndExpand(response.isbn())
                .toUri();

        return ResponseEntity
                .created(location)
                .body(response);
    }
}