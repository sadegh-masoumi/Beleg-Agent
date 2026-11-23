package dev.belegagent.evals;

import dev.belegagent.core.Beleg;

/**
 * One model answer for one document and config. Cached on disk so reruns do not call the model again.
 *
 * @param error {@code null} on success; the model call's error message otherwise
 */
record Prediction(
        String caseId,
        String sha256,
        String config,
        String model,
        Beleg beleg,
        Integer inputTokens,
        Integer outputTokens,
        Integer thinkingTokens,
        long latencyMs,
        String error) {
}
