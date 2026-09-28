package com.parvez.android.document;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BookDocumentHeadTest {
    @Test void headDoesNotOpenOrReadLargeDocumentResource() throws Exception {
        var service = mock(BookDocumentService.class);
        var resource = mock(Resource.class);
        long size = 5L * 1024 * 1024 * 1024;
        var doc = new BookDocument(UUID.randomUUID(), 1, "LOCAL", "private-key", "book.pdf",
                size, 100, "checksum", "UPLOAD", true, Instant.now());
        when(service.active(1)).thenReturn(doc);
        when(service.content(doc)).thenReturn(resource);
        // MockHttpServletResponse parses this header as an int; check long lengths directly.
        var response = new BookDocumentController(service).contentHeaders(1, false, doc.id());
        assertEquals(200, response.getStatusCode().value());
        assertEquals(size, response.getHeaders().getContentLength());
        assertNull(response.getBody());
        verify(service).active(1);
        verify(service).content(doc);
        verifyNoInteractions(resource);
    }
}
