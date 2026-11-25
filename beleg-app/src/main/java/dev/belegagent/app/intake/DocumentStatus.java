package dev.belegagent.app.intake;

public enum DocumentStatus {
    /** Stored, waiting for a worker (or for the next retry at {@code nextAttemptAt}). */
    RECEIVED,
    /** Claimed by a worker. */
    PROCESSING,
    /** Extraction stored without validation (documents processed before Phase 3). */
    EXTRACTED,
    /** All deterministic checks passed; ready for export. */
    ACCEPTED,
    /** Validation errors remain after self-correction; a human must approve or correct. */
    NEEDS_REVIEW,
    /** Approved or corrected by a human. */
    APPROVED,
    /** Permanent failure (e.g. unreadable PDF, rejected request); see {@code lastError}. Not retried. */
    FAILED,
    /** Transient failures until the retry limit was reached; see {@code lastError}. Can be requeued. */
    DEAD_LETTER;

    /** Statuses a reviewer may approve or correct. */
    public boolean reviewable() {
        return this == ACCEPTED || this == NEEDS_REVIEW || this == EXTRACTED;
    }
}
