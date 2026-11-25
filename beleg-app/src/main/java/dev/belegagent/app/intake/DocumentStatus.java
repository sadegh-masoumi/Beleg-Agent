package dev.belegagent.app.intake;

public enum DocumentStatus {
    /** Stored, waiting for a worker. */
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
    /** Extraction failed; see {@code lastError}. Retries arrive in Phase 4. */
    FAILED;

    /** Statuses a reviewer may approve or correct. */
    public boolean reviewable() {
        return this == ACCEPTED || this == NEEDS_REVIEW || this == EXTRACTED;
    }
}
