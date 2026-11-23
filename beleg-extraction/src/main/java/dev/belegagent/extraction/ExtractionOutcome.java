package dev.belegagent.extraction;

import dev.belegagent.core.Beleg;
import java.time.Duration;

/**
 * Result of one model call. Both the raw model answer and the normalized {@link Beleg} are kept (GoBD,
 * and raw answers are needed to debug evals).
 *
 * @param inputTokens  {@code null} if the provider did not report usage
 * @param outputTokens   answer tokens; {@code null} if the provider did not report usage
 * @param thinkingTokens reasoning tokens (billed like output); {@code null} if none or not reported
 * @param pagesSent      number of images sent to the model
 * @param totalPages     number of pages of the original document (1 for images)
 */
public record ExtractionOutcome(
        Beleg beleg,
        String rawResponse,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Integer thinkingTokens,
        Duration latency,
        int pagesSent,
        int totalPages) {
}
