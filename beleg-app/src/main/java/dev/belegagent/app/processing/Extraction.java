package dev.belegagent.app.processing;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.validation.ValidationResult;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One stored extraction attempt (row of table {@code extraction}).
 *
 * @param source     E_INVOICE, MODEL or MODEL_CORRECTION
 * @param attempt    1 for the first attempt, 2 for the self-correction
 * @param chosen     whether the agent used this attempt's data
 * @param validation {@code null} for documents processed before Phase 3
 */
public record Extraction(
        UUID id,
        UUID documentId,
        UUID runId,
        String source,
        int attempt,
        boolean chosen,
        String model,
        Beleg beleg,
        ValidationResult validation,
        Integer inputTokens,
        Integer outputTokens,
        Integer thinkingTokens,
        long latencyMs,
        int pagesSent,
        int totalPages,
        OffsetDateTime createdAt) {
}
