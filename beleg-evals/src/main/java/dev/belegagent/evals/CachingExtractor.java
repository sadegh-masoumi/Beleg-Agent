package dev.belegagent.evals;

import dev.belegagent.core.validation.Finding;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionException;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps a model extractor with the on-disk answer cache, the free-tier delay and the quota circuit breaker,
 * so the eval harness can run the real {@link dev.belegagent.extraction.agent.BelegAgent} without paying twice.
 *
 * <p>First attempts are cached under {@code <cache>/<config>/<prompt fingerprint>/<sha256>.json} (unchanged from
 * Phase 2, so earlier answers are reused). Corrections are cached under
 * {@code <cache>/<config>/<prompt fingerprint>/correct-<model>-<correction fingerprint>/<sha256>.json}.
 */
final class CachingExtractor implements BelegExtractor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_CONSECUTIVE_QUOTA_ERRORS = 3;

    private final String configName;
    private final String model;
    private final Path dir;
    private final Supplier<BelegExtractor> delegate;
    private final String apiKey;
    private final long delayMs;
    private BelegExtractor service;
    private int consecutiveQuotaErrors;
    private boolean lastCached;

    /**
     * @param delegate created lazily, so a fully cached run needs no API key
     */
    CachingExtractor(String configName, String model, Path dir, Supplier<BelegExtractor> delegate, String apiKey,
                     long delayMs) {
        this.configName = configName;
        this.model = model;
        this.dir = dir;
        this.delegate = delegate;
        this.apiKey = apiKey;
        this.delayMs = delayMs;
    }

    @Override
    public ExtractionOutcome extract(byte[] content, FileKind kind) {
        return cachedCall(content, () -> service().extract(content, kind));
    }

    @Override
    public ExtractionOutcome correct(byte[] content, FileKind kind, ExtractionOutcome previous, List<Finding> errors) {
        return cachedCall(content, () -> service().correct(content, kind, previous, errors));
    }

    /** Whether the last answer came from the cache (for progress output). */
    boolean lastCached() {
        return lastCached;
    }

    private ExtractionOutcome cachedCall(byte[] content, Supplier<ExtractionOutcome> call) {
        String sha = ZugferdDataset.sha256(content);
        Path file = dir.resolve(sha + ".json");
        Prediction cached = read(file);
        if (cached != null) {
            lastCached = true;
            return toOutcome(cached);
        }
        lastCached = false;
        if (consecutiveQuotaErrors >= MAX_CONSECUTIVE_QUOTA_ERRORS) {
            throw new ExtractionException("skipped: quota exhausted (" + MAX_CONSECUTIVE_QUOTA_ERRORS
                    + " consecutive HTTP 429)");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set and " + sha + " is not cached for " + dir);
        }
        try {
            ExtractionOutcome outcome = call.get();
            consecutiveQuotaErrors = 0;
            write(file, new Prediction(null, sha, configName, model, outcome.beleg(), outcome.inputTokens(),
                    outcome.outputTokens(), outcome.thinkingTokens(), outcome.latency().toMillis(), null));
            return outcome;
        } catch (RuntimeException e) {
            if (String.valueOf(e.getMessage()).contains("\"code\": 429")) {
                consecutiveQuotaErrors++;
            }
            throw e;
        } finally {
            sleep(delayMs);
        }
    }

    private BelegExtractor service() {
        if (service == null) {
            service = delegate.get();
        }
        return service;
    }

    private ExtractionOutcome toOutcome(Prediction p) {
        // The previous answer for a correction is sent as JSON of the normalized data.
        return new ExtractionOutcome(p.beleg(), JSON.writeValueAsString(p.beleg()), p.model() != null ? p.model() : model,
                p.inputTokens(), p.outputTokens(), p.thinkingTokens(), Duration.ofMillis(p.latencyMs()), 0, 0);
    }

    private static Prediction read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Prediction p = JSON.readValue(Files.readString(file), Prediction.class);
            return p.error() == null && p.beleg() != null ? p : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void write(Path file, Prediction prediction) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(prediction));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
