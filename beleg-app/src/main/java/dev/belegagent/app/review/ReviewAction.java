package dev.belegagent.app.review;

public enum ReviewAction {
    /** The chosen extraction is correct as it is. */
    APPROVE,
    /** The reviewer supplies corrected data; it is stored next to the model output, which stays unchanged. */
    CORRECT
}
