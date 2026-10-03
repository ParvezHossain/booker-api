package com.parvez.android.library;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Public library requests")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "basicAuth")
@ApiResponse(responseCode = "400", description = "Invalid fields, UUID or multipart data", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Workspace or Super Admin permission required", content = @Content)
@ApiResponse(responseCode = "404", description = "Book request not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "409", description = "Duplicate book/request or request already reviewed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
public class PublicLibraryRequestController {
    private final PublicLibraryRequestService service;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    public PublicLibraryRequestController(PublicLibraryRequestService service, ObjectMapper objectMapper, Validator validator) {
        this.service = service; this.objectMapper = objectMapper; this.validator = validator;
    }

    @Operation(summary = "Request a public library book", description = "Workspace identity and requester email come from authentication. Only title and authorName are needed. Duplicate pending workspace requests or existing public books return 409.")
    @ApiResponse(responseCode = "201", description = "Pending book request created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicLibraryBookRequest.class)))
    @PostMapping("/api/public-book-requests")
    public ResponseEntity<PublicLibraryBookRequest> submit(@Valid @RequestBody PublicLibraryRequestService.Submit input) {
        return ResponseEntity.status(201).body(service.submit(input));
    }
    @Operation(summary = "List current workspace's book requests")
    @ApiResponse(responseCode = "200", description = "Current workspace request history", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PublicLibraryBookRequest.class))))
    @GetMapping("/api/public-book-requests")
    public List<PublicLibraryBookRequest> own() { return service.own(); }

    @Operation(summary = "List all public book requests as Super Admin", description = "Optional status: PENDING, ACCEPTED or REJECTED.")
    @ApiResponse(responseCode = "200", description = "Requests for administrator review", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PublicLibraryBookRequest.class))))
    @GetMapping("/api/admin/public-book-requests")
    public List<PublicLibraryBookRequest> all(@RequestParam(required = false) String status) { return service.all(status); }

    @Operation(summary = "Accept a pending request with its PDF", description = "Super Admin only. Multipart file (application/pdf) and metadata (JSON containing publishedDate, optional description and completed; application/json or a plain form field). Title/author come from the request. Existing PDF validation applies. Private multipart limits still apply to this endpoint. Failed creation leaves the request pending. Repeated processing returns 409.")
    @ApiResponse(responseCode = "200", description = "Accepted request with public book ID", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicLibraryBookRequest.class)))
    @ApiResponse(responseCode = "413", description = "Multipart upload limit exceeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "415", description = "Invalid or unsafe PDF", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "PDF storage unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping(value = "/api/admin/public-book-requests/{requestId}/accept", consumes = "multipart/form-data")
    public PublicLibraryBookRequest accept(@PathVariable UUID requestId,
            @Schema(implementation = PublicLibraryRequestService.Metadata.class)
            @RequestPart("metadata") String metadataJson, @RequestPart("file") MultipartFile file) throws IOException {
        PublicLibraryRequestService.Metadata metadata;
        try {
            metadata = objectMapper.readValue(metadataJson, PublicLibraryRequestService.Metadata.class);
        } catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata JSON");
        }
        if (metadata == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metadata is required");
        var violations = validator.validate(metadata);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted().collect(Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        try (var input = file.getInputStream()) {
            return service.accept(requestId, metadata, file.getOriginalFilename(), file.getContentType(), input);
        }
    }
    @Operation(summary = "Reject a pending public book request", description = "Super Admin only. Already processed requests return 409. Decision notifications and retryable email are persisted transactionally.")
    @ApiResponse(responseCode = "200", description = "Rejected request", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicLibraryBookRequest.class)))
    @PostMapping("/api/admin/public-book-requests/{requestId}/reject")
    public PublicLibraryBookRequest reject(@PathVariable UUID requestId) { return service.reject(requestId); }
}
