package dev.belegagent.extraction;

import java.time.Duration;

/**
 * Connection and cost settings for one Gemini model.
 *
 * @param mediaResolution LOW, MEDIUM or HIGH; per Google, OCR quality usually saturates at MEDIUM
 * @param thinkingLevel   MINIMAL, LOW, MEDIUM, HIGH, or blank for the model default
 */
public record GeminiSettings(
        String apiKey,
        String modelName,
        String mediaResolution,
        String thinkingLevel,
        Duration timeout,
        int maxRetries) {

    public GeminiSettings {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("GEMINI_API_KEY is not configured");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Gemini model name is not configured");
        }
    }

    /** Masks the key so settings can be logged. */
    @Override
    public String toString() {
        return "GeminiSettings[model=%s, mediaResolution=%s, thinkingLevel=%s, timeout=%s, maxRetries=%d]"
                .formatted(modelName, mediaResolution, thinkingLevel, timeout, maxRetries);
    }
}
