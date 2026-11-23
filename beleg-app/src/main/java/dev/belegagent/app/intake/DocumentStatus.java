package dev.belegagent.app.intake;

public enum DocumentStatus {
    /** Stored, waiting for a worker. */
    RECEIVED,
    /** Claimed by a worker. */
    PROCESSING,
    /** Extraction stored. */
    EXTRACTED,
    /** Extraction failed; see {@code lastError}. Retries arrive in Phase 4. */
    FAILED
}
