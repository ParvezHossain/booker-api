package com.parvez.android.library;

import java.time.Instant;
import java.util.UUID;

public record PublicLibraryBookRequest(UUID id, String title, String authorName, UUID workspaceId,
        String requesterEmail, String status, Long bookId, String reviewedBy, Instant createdAt, Instant reviewedAt) {}
