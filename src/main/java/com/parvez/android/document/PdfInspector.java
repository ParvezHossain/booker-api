package com.parvez.android.document;

import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

@Component
public class PdfInspector {
    private final Semaphore parsers;
    private final int maxPages;
    private final Duration wait;
    public PdfInspector(@Value("${books.documents.max-pages:20000}") int maxPages,
                        @Value("${books.documents.max-concurrent-parsers:2}") int maximumParsers,
                        @Value("${books.documents.parser-wait:PT5S}") Duration wait) {
        if (maxPages < 1 || maximumParsers < 1 || maximumParsers > 8
                || wait.isNegative() || wait.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("PDF page limit must be positive, parsers 1–8 and wait 0–30 seconds");
        this.maxPages = maxPages;
        this.parsers = new Semaphore(maximumParsers, true);
        this.wait = wait;
    }
    public int inspect(Resource resource) throws IOException { return inspect(resource, maxPages); }
    public int inspectPublic(Resource resource) throws IOException { return inspect(resource, Integer.MAX_VALUE); }
    private int inspect(Resource resource, int pageLimit) throws IOException {
        try {
            if (!parsers.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) throw busy();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        Path temporary = null;
        try {
            try (var input = resource.getInputStream()) {
                if (!new String(input.readNBytes(5), java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                    throw invalid();
            }
            java.io.File file;
            if (resource.isFile()) file = resource.getFile();
            else {
                temporary = Files.createTempFile("booker-pdf-", ".pdf");
                try (var input = resource.getInputStream()) { Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                file = temporary.toFile();
            }
            try (var pdf = Loader.loadPDF(file)) {
                int pages = pdf.getNumberOfPages();
                var catalog = pdf.getDocumentCatalog();
                if (pdf.isEncrypted() || pages < 1 || pages > pageLimit || catalog.getOpenAction() != null
                        || catalog.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)
                        || (catalog.getNames() != null && (catalog.getNames().getJavaScript() != null
                        || catalog.getNames().getEmbeddedFiles() != null))) throw invalid();
                for (var page : pdf.getPages()) {
                    if (page.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)) throw invalid();
                }
                rejectActiveContent(catalog.getCOSObject());
                return pages;
            } catch (IOException | IllegalArgumentException ex) { throw invalid(); }
        } finally {
            parsers.release();
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }
    private ResponseStatusException busy() {
        return new PdfCapacityException("PDF validation is busy; retry later", 5);
    }
    private void rejectActiveContent(org.apache.pdfbox.cos.COSBase root) {
        // PDF objects can be cyclic; bound traversal and track identity rather than recursing.
        var pending = new java.util.ArrayDeque<org.apache.pdfbox.cos.COSBase>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<org.apache.pdfbox.cos.COSBase, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            if (seen.size() > 250000) throw invalid();
            if (value instanceof org.apache.pdfbox.cos.COSObject object) {
                if (object.getObject() != null) pending.add(object.getObject());
            } else if (value instanceof org.apache.pdfbox.cos.COSDictionary dictionary) {
                String action = dictionary.getNameAsString(org.apache.pdfbox.cos.COSName.S);
                if (dictionary.containsKey(org.apache.pdfbox.cos.COSName.JS)
                        || dictionary.containsKey(org.apache.pdfbox.cos.COSName.XFA)
                        || java.util.Set.of("JavaScript", "Launch", "SubmitForm", "ImportData", "Rendition", "GoToE").contains(action == null ? "" : action)) throw invalid();
                for (var child : dictionary.getValues()) if (child != null) pending.add(child);
            } else if (value instanceof org.apache.pdfbox.cos.COSArray array) {
                for (var child : array) if (child != null) pending.add(child);
            }
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Use a valid, unencrypted PDF without document scripts or attachments, within the page limit");
    }
}
