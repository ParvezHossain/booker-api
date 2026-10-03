package com.parvez.android.reading;

import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/books")
@Tag(name = "Reading progress")
@ApiResponses({@ApiResponse(responseCode = "400", description = "Invalid page, UUID, revision or book ID list", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "403", description = "A workspace account is required", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
        @ApiResponse(responseCode = "404", description = "Book or PDF not found in your workspace", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
public class ReadingProgressController {
    private final ReadingProgressService progress;
    public ReadingProgressController(ReadingProgressService progress) { this.progress = progress; }
    @Operation(summary = "Get personal progress", description = "Before first reading: currentPage=0, resumePage=1, lastReadAt=null. Progress is private to the authenticated account; totalPages comes from the active PDF.")
    @ApiResponse(responseCode = "200", description = "Current personal reading progress", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReadingProgress.class)))
    @GetMapping("/{bookId}/reading-progress")
    public ResponseEntity<ReadingProgress> get(@PathVariable long bookId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.get(bookId));
    }
    @Operation(summary = "Save personal progress", description = "Supply documentId, currentPage (1..totalPages), required non-null version (0 on first save), and a UUID operationId reused on retry. A stale revision returns 409 with the current ReadingProgress body; its maximum page is merged, but resume position is preserved. A future revision returns 409 without mutation. A replaced document returns a 409 ApiError. Completion never changes Book.completed.")
    @ApiResponse(responseCode = "409", description = "Revision conflict (ReadingProgress) or replaced document/reused operation (ApiError)",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(oneOf = {ReadingProgress.class, com.parvez.android.dto.ApiError.class})))
    @ApiResponse(responseCode = "200", description = "Current personal reading progress", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReadingProgress.class)))
    @PutMapping("/{bookId}/reading-progress")
    public ResponseEntity<ReadingProgress> update(@PathVariable long bookId, @Valid @RequestBody ReadingProgressService.Update request) {
        var result = progress.update(bookId, request);
        return ResponseEntity.status(result.conflict() ? 409 : 200).cacheControl(CacheControl.noStore()).body(result.progress());
    }
    @Operation(summary = "Get document and personal progress summaries", description = "1–100 authorized bookIds. Books without a PDF return null document and progress. Existing book responses are unchanged.")
    @ApiResponse(responseCode = "200", description = "Document and progress summaries", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ReadingProgressService.Summary.class))))
    @GetMapping("/reading-summaries")
    public ResponseEntity<List<ReadingProgressService.Summary>> summaries(@RequestParam List<Long> bookIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.summaries(bookIds));
    }
}
