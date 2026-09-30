package com.parvez.android.document;

import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Shared headers and version pinning for private and global PDF streams. */
public final class DocumentHttpResponse {
    private DocumentHttpResponse() {}
    public static BookDocument pin(BookDocument doc, UUID documentId) {
        if (documentId != null && !documentId.equals(doc.id()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The PDF was replaced; reopen the book");
        return doc;
    }
    public static ResponseEntity.BodyBuilder headers(BookDocument doc, boolean download) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore()).eTag('"' + doc.id().toString() + '"')
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder(download ? "attachment" : "inline")
                        .filename(doc.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff");
    }
}
