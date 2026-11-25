package dev.belegagent.app.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Model, rate-limit and rendering settings.
 *
 * @param provider             {@code gemini}, or {@code simulated} for load tests (no network, no cost)
 * @param geminiModel          first-tier (cheap) model
 * @param geminiStrongModel    second-tier model, used only for self-correction
 * @param mediaResolution      LOW, MEDIUM or HIGH
 * @param thinkingLevel        MINIMAL, LOW, MEDIUM, HIGH, or blank for the model default
 * @param maxRetries           LangChain4j's in-call retries; 0 by default because the queue retries with jitter and
 *                             respects the cluster rate limit, in-call retries do neither
 * @param requestsPerMinute    cluster-wide quota for {@code geminiModel}; 0 = unlimited
 * @param strongRequestsPerMinute cluster-wide quota for {@code geminiStrongModel}; 0 = unlimited
 * @param maxRateLimitWait     longest wait for a rate-limit token before the document is requeued
 * @param simulatedLatency     mean latency of the simulated model
 * @param simulatedFailureRate share of simulated calls failing like HTTP 429
 * @param renderDpi            DPI for rendering PDF pages to images
 * @param maxPages             maximum number of PDF pages sent to the model
 */
@ConfigurationProperties("beleg.llm")
public record LlmProperties(
        @DefaultValue("gemini") String provider,
        String geminiApiKey,
        String geminiModel,
        String geminiStrongModel,
        String mediaResolution,
        String thinkingLevel,
        @DefaultValue("90s") Duration timeout,
        @DefaultValue("0") int maxRetries,
        @DefaultValue("0") int requestsPerMinute,
        @DefaultValue("0") int strongRequestsPerMinute,
        @DefaultValue("30s") Duration maxRateLimitWait,
        @DefaultValue("4s") Duration simulatedLatency,
        @DefaultValue("0") double simulatedFailureRate,
        @DefaultValue("200") int renderDpi,
        @DefaultValue("3") int maxPages) {

    public boolean simulated() {
        return "simulated".equalsIgnoreCase(provider);
    }

    public String strongModelOrPrimary() {
        return geminiStrongModel == null || geminiStrongModel.isBlank() ? geminiModel : geminiStrongModel;
    }
}
