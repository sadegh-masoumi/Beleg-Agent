package dev.belegagent.app.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Worker settings (ADR 0006).
 *
 * @param pollIntervalMs  pause between queue polls
 * @param concurrency     documents processed in parallel per worker (virtual threads); the model quota, not this
 *                        number, usually limits throughput
 * @param lease           how long a claimed document belongs to this worker; must exceed the slowest processing
 * @param maxAttempts     attempts (claims) before a transiently failing document goes to DEAD_LETTER
 * @param retryBaseDelay  first retry delay cap; doubles per attempt (exponential backoff with full jitter)
 * @param retryMaxDelay   upper bound of the retry delay
 * @param shutdownGrace   how long a stopping worker waits for running documents
 */
@ConfigurationProperties("beleg.worker")
public record WorkerProperties(
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("4") int concurrency,
        @DefaultValue("5m") Duration lease,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5s") Duration retryBaseDelay,
        @DefaultValue("10m") Duration retryMaxDelay,
        @DefaultValue("60s") Duration shutdownGrace) {

    public WorkerProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("beleg.worker.concurrency must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("beleg.worker.max-attempts must be at least 1");
        }
    }
}
