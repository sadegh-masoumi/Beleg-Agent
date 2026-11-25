package dev.belegagent.extraction.agent;

/** Where the data of one attempt came from. */
public enum Source {
    /** Embedded ZUGFeRD / XRechnung XML, read deterministically (no model). */
    E_INVOICE,
    /** First vision-model call with the cheap model. */
    MODEL,
    /** Self-correction call: validator errors sent back, usually to the stronger model. */
    MODEL_CORRECTION
}
