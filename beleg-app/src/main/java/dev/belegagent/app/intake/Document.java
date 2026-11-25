package dev.belegagent.app.intake;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One uploaded file and its processing state (row of table {@code document}). */
public record Document(
        UUID id,
        String tenantId,
        String contentSha256,
        String objectKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        int attempts,
        String lastError,
        OffsetDateTime nextAttemptAt,
        String lockedBy,
        OffsetDateTime lockedUntil,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
