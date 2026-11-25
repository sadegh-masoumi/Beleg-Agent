package dev.belegagent.core.validation;

/** Which mandatory-field rules were applied. */
public enum RuleSet {
    /** Kleinbetragsrechnung, gross total at most 250 € (§33 UStDV). */
    SMALL_AMOUNT,
    /** Full invoice (§14 Abs. 4 UStG). */
    FULL_INVOICE
}
