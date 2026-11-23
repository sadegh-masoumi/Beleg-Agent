package dev.belegagent.app.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini and rendering settings.
 *
 * @param geminiModel       first-tier (cheap) model
 * @param geminiStrongModel second-tier model, used from Phase 3 when validation fails
 * @param mediaResolution   LOW, MEDIUM or HIGH
 * @param thinkingLevel     MINIMAL, LOW, MEDIUM, HIGH, or blank for the model default
 * @param renderDpi         DPI for rendering PDF pages to images
 * @param maxPages          maximum number of PDF pages sent to the model
 */
@ConfigurationProperties("beleg.llm")
public record LlmProperties(
        String geminiApiKey,
        String geminiModel,
        String geminiStrongModel,
        String mediaResolution,
        String thinkingLevel,
        Duration timeout,
        int maxRetries,
        int renderDpi,
        int maxPages) {
}
