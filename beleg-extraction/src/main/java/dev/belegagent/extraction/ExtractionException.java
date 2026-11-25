package dev.belegagent.extraction;

import java.time.Duration;

/**
 * Extraction failed. {@link #retryable()} tells the worker whether to retry later (rate limit, timeout, provider
 * overload) or to give up at once (unreadable PDF, rejected request).
 */
public class ExtractionException extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;

    /** A permanent failure. */
    public ExtractionException(String message) {
        this(message, null, false, null);
    }

    /** A permanent failure. */
    public ExtractionException(String message, Throwable cause) {
        this(message, cause, false, null);
    }

    protected ExtractionException(String message, Throwable cause, boolean retryable, Duration retryAfter) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    /** A transient failure: retrying later may succeed. */
    public static ExtractionException retryable(String message, Throwable cause) {
        return new ExtractionException(message, cause, true, null);
    }

    /** A transient failure with a known earliest retry time (e.g. the local rate limiter). */
    public static ExtractionException retryableAfter(String message, Duration retryAfter) {
        return new ExtractionException(message, null, true, retryAfter);
    }

    public boolean retryable() {
        return retryable;
    }

    /** Earliest sensible retry, {@code null} if unknown. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
