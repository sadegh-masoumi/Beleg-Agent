package dev.belegagent.app.processing;

import dev.belegagent.core.Beleg;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One stored model call (row of table {@code extraction}). */
public record Extraction(
        UUID id,
        UUID documentId,
        String model,
        Beleg beleg,
        Integer inputTokens,
        Integer outputTokens,
        Integer thinkingTokens,
        long latencyMs,
        int pagesSent,
        int totalPages,
        OffsetDateTime createdAt) {
}
