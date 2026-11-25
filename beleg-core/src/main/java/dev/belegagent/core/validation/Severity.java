package dev.belegagent.core.validation;

/**
 * How serious a finding is.
 * <ul>
 *   <li>{@link #ERROR}: a mandatory field is missing or the amounts do not add up. Triggers one self-correction
 *       attempt and, if it remains, sends the document to human review.</li>
 *   <li>{@link #WARNING}: unusual but possibly correct (e.g. an unknown tax rate, a document-level discount).
 *       Shown to the reviewer, does not block on its own.</li>
 * </ul>
 */
public enum Severity {
    ERROR,
    WARNING
}
