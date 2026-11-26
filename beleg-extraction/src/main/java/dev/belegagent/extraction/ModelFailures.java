package dev.belegagent.extraction;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;

/** Classifies a failed model call as transient (retry later) or permanent. */
public final class ModelFailures {

    private ModelFailures() {
    }

    /**
     * Retryable: rate limits (429), timeouts, provider errors (5xx, e.g. 503 "high demand"), network errors.
     * Permanent: authentication, invalid request, content filter and other client errors. Unknown errors count as
     * transient; the retry limit and the dead-letter status stop endless retries.
     */
    public static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RetriableException || t instanceof HttpTimeoutException || t instanceof IOException) {
                return true;
            }
            if (t instanceof NonRetriableException) {
                return false;
            }
            if (t instanceof HttpException http) {
                int code = http.statusCode();
                return code == 408 || code == 429 || code >= 500;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return true;
    }
}
