package dev.belegagent.app.processing;

import dev.belegagent.core.validation.Finding;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionException;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.function.Supplier;

/**
 * Wraps a model extractor with the cluster-wide rate limiter and a call timer
 * ({@code beleg.model.calls}, tags: model, kind = extract|correct, outcome = ok|retryable|permanent).
 */
final class GuardedExtractor implements BelegExtractor {

    private final BelegExtractor delegate;
    private final String model;
    private final int requestsPerMinute;
    private final ModelRateLimiter limiter;
    private final MeterRegistry registry;

    GuardedExtractor(BelegExtractor delegate, String model, int requestsPerMinute, ModelRateLimiter limiter,
                     MeterRegistry registry) {
        this.delegate = delegate;
        this.model = model;
        this.requestsPerMinute = requestsPerMinute;
        this.limiter = limiter;
        this.registry = registry;
    }

    @Override
    public ExtractionOutcome extract(byte[] content, FileKind kind) {
        return guarded("extract", () -> delegate.extract(content, kind));
    }

    @Override
    public ExtractionOutcome correct(byte[] content, FileKind kind, ExtractionOutcome previous, List<Finding> errors) {
        return guarded("correct", () -> delegate.correct(content, kind, previous, errors));
    }

    private ExtractionOutcome guarded(String kind, Supplier<ExtractionOutcome> call) {
        limiter.acquire(model, requestsPerMinute);
        Timer.Sample sample = Timer.start(registry);
        String outcome = "ok";
        try {
            return call.get();
        } catch (ExtractionException e) {
            outcome = e.retryable() ? "retryable" : "permanent";
            throw e;
        } catch (RuntimeException e) {
            outcome = "permanent";
            throw e;
        } finally {
            sample.stop(registry.timer("beleg.model.calls", "model", model, "kind", kind, "outcome", outcome));
        }
    }
}
