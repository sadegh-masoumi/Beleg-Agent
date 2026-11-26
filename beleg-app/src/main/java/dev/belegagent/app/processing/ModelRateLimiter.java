package dev.belegagent.app.processing;

import dev.belegagent.extraction.ExtractionException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster-wide requests-per-minute limit per model: one Bucket4j token bucket per model in PostgreSQL
 * ({@code rate_limit_bucket}), shared by every worker. A per-JVM limiter would let N workers send N × the quota.
 *
 * <p>The bucket holds a tenth of a minute's quota as burst and refills continuously, so calls are spread over the
 * minute instead of all firing at its start. A caller waits for a token at most {@code maxWait}; beyond that the
 * document is put back into the queue (backpressure) instead of blocking a worker slot for long.
 */
public final class ModelRateLimiter {

    private final ProxyManager<String> buckets;
    private final Duration maxWait;
    private final Timer waitTimer;
    private final MeterRegistry registry;
    private final Map<String, BucketProxy> byModel = new ConcurrentHashMap<>();

    public ModelRateLimiter(ProxyManager<String> buckets, Duration maxWait, MeterRegistry registry) {
        this.buckets = buckets;
        this.maxWait = maxWait;
        this.registry = registry;
        this.waitTimer = Timer.builder("beleg.model.ratelimit.wait")
                .description("Time spent waiting for a model rate-limit token")
                .register(registry);
    }

    /**
     * Blocks until a call to {@code model} is allowed.
     *
     * @param requestsPerMinute quota; 0 or less means unlimited
     * @throws ExtractionException retryable, with {@code retryAfter}, if the wait would exceed {@code maxWait}
     */
    public void acquire(String model, int requestsPerMinute) {
        if (requestsPerMinute <= 0) {
            return;
        }
        BucketProxy bucket = byModel.computeIfAbsent(model, m -> buckets.builder()
                .build("model:" + m, () -> configuration(requestsPerMinute)));
        long start = System.nanoTime();
        while (true) {
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                waitTimer.record(Duration.ofNanos(System.nanoTime() - start));
                return;
            }
            Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            if (waited.plus(wait).compareTo(maxWait) > 0) {
                registry.counter("beleg.model.ratelimit.rejected", "model", model).increment();
                throw ExtractionException.retryableAfter(
                        "Rate limit of " + requestsPerMinute + " requests/min for " + model + " reached", wait);
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ExtractionException.retryable("Interrupted while waiting for the rate limiter", e);
            }
        }
    }

    static BucketConfiguration configuration(int requestsPerMinute) {
        Bandwidth limit = BandwidthBuilder.builder()
                .capacity(Math.max(1, requestsPerMinute / 10))
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }
}
