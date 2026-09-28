package com.parvez.android.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {
    private final Path root;
    public LocalFileStorageService(@Value("${books.storage.directory:./data/books}") String directory) throws IOException {
        root = Files.createDirectories(Path.of(directory).toAbsolutePath().normalize()).toRealPath();
    }
    @Override public String provider() { return "LOCAL"; }
    @Override public StoredFile upload(InputStream input, long maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("Upload limit must be positive");
        java.util.Objects.requireNonNull(input, "Upload input is required");
        String key = UUID.randomUUID() + ".pdf";
        Path target = path(key);
        Path staging = Files.createTempFile(root, "upload-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var output = Files.newOutputStream(staging)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > maximumBytes - size) throw new StorageUploadException(StorageUploadException.Reason.TOO_LARGE);
                    size += read;
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size == 0) throw new StorageUploadException(StorageUploadException.Reason.EMPTY);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(key, size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } finally { Files.deleteIfExists(staging); }
    }
    @Override public Resource download(String key) throws IOException {
        Path file = path(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Document content unavailable");
        return new FileSystemResource(file);
    }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
    @Override public boolean exists(String key) { return Files.isRegularFile(path(key), LinkOption.NOFOLLOW_LINKS); }
    private Path path(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf"))
            throw new IllegalArgumentException("Invalid storage key");
        return root.resolve(key);
    }
}
