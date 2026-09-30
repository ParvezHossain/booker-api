package com.parvez.android.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@ApiResponses({@ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Book or PDF not found in your workspace")})
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
public class BookDocumentController {
    private final BookDocumentService documents;
    public BookDocumentController(BookDocumentService documents) { this.documents = documents; }

    @Operation(summary = "Upload or replace the active PDF", description = "Multipart file, PDF only. Idempotency-Key is a UUID reused for retries of the same upload. Returns metadata, never storage paths. Creates an immutable version; old versions are retained. Workspace authorization is required.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "PDF stored or previous idempotent result returned"),
            @ApiResponse(responseCode = "400", description = "Missing file, invalid filename or operation UUID"),
            @ApiResponse(responseCode = "403", description = "Workspace file storage budget exhausted"),
            @ApiResponse(responseCode = "413", description = "File or multipart request exceeds configured limit"),
            @ApiResponse(responseCode = "415", description = "Not a supported, safe PDF"),
            @ApiResponse(responseCode = "503", description = "PDF validator busy or storage unavailable")})
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
    @GetMapping
    public ResponseEntity<BookDocument.DocumentResponse> metadata(@PathVariable long bookId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.active(bookId).response());
    }
    @Operation(summary = "Read the PDF", description = "Authorized streaming PDF with byte ranges (206, invalid ranges 416). Optional documentId pins the active version and returns 409 if replaced. download=true uses attachment disposition. GET and HEAD are supported.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "PDF content or HEAD metadata"),
            @ApiResponse(responseCode = "206", description = "Requested byte range"),
            @ApiResponse(responseCode = "409", description = "Document version was replaced"),
            @ApiResponse(responseCode = "416", description = "Invalid or unsatisfiable byte range"),
            @ApiResponse(responseCode = "503", description = "Storage unavailable")})
    @GetMapping("/content")
    public ResponseEntity<Resource> content(@PathVariable long bookId,
            @RequestParam(defaultValue = "false") boolean download, @RequestParam(required = false) UUID documentId) {
        var doc = activeDocument(bookId, documentId);
        return DocumentHttpResponse.headers(doc, download).body(documents.content(doc));
    }

    @Operation(summary = "Get PDF content headers without reading its bytes", description = "Requires the same workspace authorization as GET. Checks content availability, ignores Range, and returns the complete document length. Optional documentId pins the active version.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "PDF content headers only"),
            @ApiResponse(responseCode = "409", description = "Document version was replaced"),
            @ApiResponse(responseCode = "503", description = "Storage unavailable")})
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
