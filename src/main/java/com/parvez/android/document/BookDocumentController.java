package com.parvez.android.document;

import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/books/{bookId}/document")
@Tag(name = "Book documents")
@ApiResponses({@ApiResponse(responseCode = "400", description = "Invalid book ID, document UUID or query value", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "403", description = "A workspace account is required", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
        @ApiResponse(responseCode = "404", description = "Book or PDF not found in your workspace", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
public class BookDocumentController {
    private final BookDocumentService documents;
    public BookDocumentController(BookDocumentService documents) { this.documents = documents; }

    @Operation(summary = "Upload or replace the active PDF", description = "Multipart file, PDF only. Idempotency-Key is a UUID reused for retries of the same upload. Returns metadata, never storage paths. Creates an immutable version; old versions are retained. Workspace authorization is required.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "PDF stored or previous idempotent result returned", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "Missing file, invalid filename or operation UUID", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Workspace file storage budget exhausted", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "File or multipart request exceeds configured limit", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "415", description = "Not a supported, safe PDF", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Upload capacity or PDF validator busy (Retry-After), or storage unavailable", headers = @Header(name = "Retry-After", description = "Retry delay in seconds when capacity is busy", schema = @Schema(type = "integer")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookDocument.DocumentResponse> upload(@PathVariable long bookId,
            @RequestHeader("Idempotency-Key") UUID operation, @RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            var doc = documents.upload(bookId, operation, file.getOriginalFilename(), file.getContentType(), input, "UPLOAD");
            return ResponseEntity.created(URI.create("/api/books/" + bookId + "/document"))
                    .cacheControl(CacheControl.noStore()).body(doc.response());
        }
    }
    @Operation(summary = "Get active PDF metadata")
    @ApiResponse(responseCode = "200", description = "Active PDF metadata", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookDocument.DocumentResponse.class)))
    @GetMapping
    public ResponseEntity<BookDocument.DocumentResponse> metadata(@PathVariable long bookId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.active(bookId).response());
    }
    @Operation(summary = "Read the PDF", description = "Authorized streaming PDF with byte ranges (206, invalid ranges 416). Optional documentId pins the active version and returns 409 if replaced. download=true uses attachment disposition. GET and HEAD are supported.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Complete PDF bytes", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "206", description = "Requested byte range", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")), headers = @Header(name = "Content-Range", description = "Served bytes and total length", schema = @Schema(type = "string", example = "bytes 0-65535/100000"))),
            @ApiResponse(responseCode = "409", description = "Document version was replaced", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "416", description = "Invalid or unsatisfiable byte range", content = @Content),
            @ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @Parameter(name = "Range", in = ParameterIn.HEADER, description = "Optional byte range handled by Spring MVC; ignored by HEAD", example = "bytes=0-65535")
    @GetMapping("/content")
    public ResponseEntity<Resource> content(@PathVariable long bookId,
            @RequestParam(defaultValue = "false") boolean download, @RequestParam(required = false) UUID documentId) {
        var doc = activeDocument(bookId, documentId);
        return DocumentHttpResponse.headers(doc, download).body(documents.content(doc));
    }

    @Operation(summary = "Get PDF content headers without reading its bytes", description = "Requires the same workspace authorization as GET. Checks content availability, ignores Range, and returns the complete document length. Optional documentId pins the active version.")
    @ApiResponse(responseCode = "200", description = "PDF content headers only", content = @Content, headers = @Header(name = "Content-Length", description = "Complete PDF size in bytes", schema = @Schema(type = "integer", format = "int64")))
    @ApiResponse(responseCode = "400", description = "Invalid book ID, document UUID or query value", content = @Content)
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "A workspace account is required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Book or active PDF not found", content = @Content)
    @ApiResponse(responseCode = "409", description = "Document version was replaced", content = @Content)
    @ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content)
    @RequestMapping(value = "/content", method = RequestMethod.HEAD)
    public ResponseEntity<Void> contentHeaders(@PathVariable long bookId,
            @RequestParam(defaultValue = "false") boolean download, @RequestParam(required = false) UUID documentId) {
        var doc = activeDocument(bookId, documentId);
        // Resolve the resource to check availability, without opening a content stream.
        documents.content(doc);
        return DocumentHttpResponse.headers(doc, download).contentLength(doc.fileSize()).build();
    }

    private BookDocument activeDocument(long bookId, UUID documentId) {
        return DocumentHttpResponse.pin(documents.active(bookId), documentId);
    }
}
