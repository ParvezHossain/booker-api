package com.parvez.android.library;

import com.parvez.android.document.*;
import com.parvez.android.dto.ApiError;
import com.parvez.android.dto.BookRequest;
import com.parvez.android.dto.BookResponse;
import com.parvez.android.reading.ReadingProgress;
import com.parvez.android.reading.ReadingProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public-books")
@Tag(name = "Public Library", description = "Global books managed by Super Admin; reading progress belongs to the authenticated workspace")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request fields, IDs, page or revision", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
        @ApiResponse(responseCode = "403", description = "Super Admin required for management; workspace account required for progress", content = @Content),
        @ApiResponse(responseCode = "404", description = "Public book or PDF not found; private books cannot be accessed here", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
public class PublicBookController {
    private final PublicBookService books;
    private final BookDocumentService documents;
    private final ReadingProgressService progress;
    public PublicBookController(PublicBookService books, BookDocumentService documents, ReadingProgressService progress) {
        this.books = books; this.documents = documents; this.progress = progress;
    }
    @Operation(summary = "List global public books", description = "Authenticated accounts can list public books across all workspaces. Optional author/title filters are exact matches, both means AND. Returns an unpaginated array, like the private library. Fetch reading-summaries for current-workspace progress.")
    @ApiResponse(responseCode = "200", description = "Public books, or an empty array", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = BookResponse.class))))
    @GetMapping
    public List<BookResponse> list(@RequestParam(required = false) String author, @RequestParam(required = false) String title) {
        return books.list(author, title);
    }
    @Operation(summary = "Get global public book details", description = "A private book ID always returns 404, even for its owner or Super Admin.")
    @ApiResponse(responseCode = "200", description = "Public book metadata", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookResponse.class)))
    @GetMapping("/{bookId}")
    public BookResponse get(@PathVariable long bookId) { return books.get(bookId); }
    @Operation(summary = "Create a public book — Super Admin only", description = "Uses the current BookRequest schema. Exact author/title pairs are unique globally within the public library, independent of private pairs. No book count quota; workspaceId is never accepted. No private SSE event is emitted.")
    @ApiResponse(responseCode = "201", description = "Public book created; Location points to its public detail route", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "409", description = "Duplicate public author/title pair", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<BookResponse> create(@Valid @RequestBody BookRequest request) {
        var book = books.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{bookId}").buildAndExpand(book.id()).toUri();
        return ResponseEntity.created(location).body(book);
    }
    @Operation(summary = "Update public metadata — Super Admin only", description = "Full BookRequest replacement; identity/library scope cannot be changed. Does not replace the PDF or alter reading progress.")
    @ApiResponse(responseCode = "409", description = "Duplicate public author/title pair", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "200", description = "Updated public book metadata", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookResponse.class)))
    @PutMapping("/{bookId}")
    public BookResponse update(@PathVariable long bookId, @Valid @RequestBody BookRequest request) { return books.update(bookId, request); }
    @Operation(summary = "Delete public book — Super Admin only", description = "Hard delete following existing relational cascade conventions. Removes documents and all workspace progress/receipts transactionally, and durably queues every retained file version for asynchronous deletion. Further reads return 404; repeated delete of a missing book returns 404. Never deletes private books.")
    @ApiResponse(responseCode = "204", description = "Book removed; file cleanup queued", content = @Content)
    @DeleteMapping("/{bookId}")
    public ResponseEntity<Void> delete(@PathVariable long bookId) { books.delete(bookId); return ResponseEntity.noContent().build(); }
    @Operation(summary = "Upload or replace public PDF — Super Admin only", description = "Streams a raw application/pdf body; required fileName query and UUID Idempotency-Key header. No application book-count, PDF-size, page-count or workspace-storage quota for public uploads. Raw streaming avoids private servlet multipart limits. File name, content safety and parser admission checks still apply; empty PDFs fail. Retries reuse the UUID for the same file/book/admin; original metadata may no longer be active. Validated immutable replacement preserves prior versions; refetch active metadata after success.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "201", description = "PDF stored or original retry result returned", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "415", description = "Unsupported MIME, filename or unsafe/invalid/encrypted PDF", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Storage unavailable or upload/parser capacity busy (Retry-After)", headers = @Header(name = "Retry-After", description = "Retry delay in seconds when capacity is busy", schema = @Schema(type = "integer")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping(value = "/{bookId}/document", consumes = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<BookDocument.DocumentResponse> upload(@PathVariable long bookId,
            @RequestHeader("Idempotency-Key") UUID operation, @RequestParam String fileName, HttpServletRequest request) throws IOException {
        try (var input = request.getInputStream()) {
            var doc = documents.uploadPublic(bookId, operation, fileName, input);
            return ResponseEntity.created(URI.create("/api/public-books/" + bookId + "/document"))
                    .cacheControl(CacheControl.noStore()).body(doc.response());
        }
    }
    @Operation(summary = "Get active public PDF metadata")
    @ApiResponse(responseCode = "200", description = "Active public PDF metadata", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookDocument.DocumentResponse.class)))
    @GetMapping("/{bookId}/document")
    public ResponseEntity<BookDocument.DocumentResponse> metadata(@PathVariable long bookId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.activePublic(bookId).response());
    }
    @Operation(summary = "Read a public PDF", description = "Authenticated streaming with Range support. Optional documentId pins the active version; download=true selects attachment. No storage path or credentials are exposed.")
    @ApiResponse(responseCode = "206", description = "Requested byte range", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")), headers = @Header(name = "Content-Range", description = "Served bytes and total length", schema = @Schema(type = "string", example = "bytes 0-65535/100000")))
    @ApiResponse(responseCode = "409", description = "Pinned document replaced", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "416", description = "Invalid/unsatisfiable byte range", content = @Content)
    @ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @Parameter(name = "Range", in = ParameterIn.HEADER, description = "Optional byte range handled by Spring MVC; ignored by HEAD", example = "bytes=0-65535")
    @ApiResponse(responseCode = "200", description = "Complete PDF bytes", content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{bookId}/document/content")
    public ResponseEntity<Resource> content(@PathVariable long bookId, @RequestParam(defaultValue = "false") boolean download,
            @RequestParam(required = false) UUID documentId) {
        var doc = DocumentHttpResponse.pin(documents.activePublic(bookId), documentId);
        return DocumentHttpResponse.headers(doc, download).body(documents.content(doc));
    }
    @Operation(summary = "Inspect public PDF headers", description = "Same authentication/version checks as GET; checks availability without reading bytes, returns complete length and ignores Range.")
    @ApiResponse(responseCode = "409", description = "Pinned document replaced", content = @Content)
    @ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content)
    @ApiResponse(responseCode = "200", description = "Complete PDF headers without a response body", content = @Content, headers = @Header(name = "Content-Length", description = "Complete PDF size in bytes", schema = @Schema(type = "integer", format = "int64")))
    @ApiResponse(responseCode = "400", description = "Invalid book ID, document UUID or query value", content = @Content)
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Access denied for this account", content = @Content)
    @ApiResponse(responseCode = "404", description = "Book or active PDF not found", content = @Content)
    @RequestMapping(value = "/{bookId}/document/content", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable long bookId, @RequestParam(defaultValue = "false") boolean download,
            @RequestParam(required = false) UUID documentId) {
        var doc = DocumentHttpResponse.pin(documents.activePublic(bookId), documentId);
        documents.content(doc);
        return DocumentHttpResponse.headers(doc, download).contentLength(doc.fileSize()).build();
    }
    @Operation(summary = "Get current workspace's public reading progress", description = "Shared by users of the same workspace; isolated from all other workspaces and private personal progress. First open: currentPage=0, resumePage=1, version=0, lastReadAt=null. Super Admin has no workspace and receives 403.")
    @ApiResponse(responseCode = "200", description = "Current workspace reading progress", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReadingProgress.class)))
    @GetMapping("/{bookId}/reading-progress")
    public ResponseEntity<ReadingProgress> getProgress(@PathVariable long bookId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.getPublic(bookId));
    }
    @Operation(summary = "Save current workspace's public reading progress", description = "Same Update schema as private progress: active documentId, currentPage 1..totalPages, required non-null version, UUID operationId. Revision/receipt scope is workspace, so teammates share progress and retry identity. Percentage 0..100 is derived from maximum page reached; never trust a supplied percentage or workspaceId. Stale/future revisions return current ReadingProgress with 409; stale maximum can merge while resume is preserved. Replacement/reused operation returns ApiError. Completion is independent of manual Book.completed.")
    @ApiResponse(responseCode = "409", description = "Revision conflict or replaced document/reused operation", content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {ReadingProgress.class, ApiError.class})))
    @ApiResponse(responseCode = "200", description = "Saved workspace reading progress", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReadingProgress.class)))
    @PutMapping("/{bookId}/reading-progress")
    public ResponseEntity<ReadingProgress> updateProgress(@PathVariable long bookId, @Valid @RequestBody ReadingProgressService.Update request) {
        var result = progress.updatePublic(bookId, request);
        return ResponseEntity.status(result.conflict() ? 409 : 200).cacheControl(CacheControl.noStore()).body(result.progress());
    }
    @Operation(summary = "Batch public PDF/current-workspace progress summaries", description = "1–100 public bookIds. Same Summary response shape as private library; no document means null document/progress. Every requested ID must be public; no arbitrary workspace selector.")
    @ApiResponse(responseCode = "200", description = "Public document and workspace progress summaries", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReadingProgressService.Summary.class))))
    @GetMapping("/reading-summaries")
    public ResponseEntity<List<ReadingProgressService.Summary>> summaries(@RequestParam List<Long> bookIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.summariesPublic(bookIds));
    }
}
