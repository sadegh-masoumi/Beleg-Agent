package dev.belegagent.extraction.agent;

/** What happens with the document after processing. */
public enum Decision {
    /** All deterministic checks passed. */
    ACCEPTED,
    /** Errors remain: a human must approve or correct the data before export. */
    NEEDS_REVIEW
}
