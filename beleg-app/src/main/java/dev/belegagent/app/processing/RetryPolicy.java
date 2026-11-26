package dev.belegagent.app.processing;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter (AWS Architecture Blog, "Exponential Backoff and Jitter"):
 * the delay after attempt {@code n} is uniform in {@code [0, min(maxDelay, baseDelay × 2^(n-1))]}.
 * Full jitter spreads retries of many documents that failed together (e.g. a provider 503 burst), so they do not
 * hit the model again in lockstep.
 */
final class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final RandomGenerator random;

    RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, RandomGenerator random) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.random = random;
    }

    /**
     * @param attempts   attempts made so far, including the one that just failed
     * @param notBefore  earliest retry known from the failure (e.g. rate limiter), or {@code null}
     * @return the delay before the next attempt, or empty if the attempt limit is reached (dead-letter)
     */
    Optional<Duration> delayAfter(int attempts, Duration notBefore) {
        if (attempts >= maxAttempts) {
            return Optional.empty();
        }
        int exponent = Math.min(Math.max(attempts - 1, 0), 30);
        long capMillis = Math.min(maxDelay.toMillis(), baseDelay.toMillis() << exponent);
        Duration jitter = Duration.ofMillis(capMillis <= 0 ? 0 : random.nextLong(capMillis + 1));
        return Optional.of(notBefore != null && notBefore.compareTo(jitter) > 0 ? notBefore : jitter);
    }

    int maxAttempts() {
        return maxAttempts;
    }
}
