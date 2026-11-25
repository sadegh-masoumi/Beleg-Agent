package dev.belegagent.core.validation;

/**
 * One rule violation.
 *
 * @param code     stable identifier, e.g. {@code TAX_LINE_TAX_MISMATCH} (used in evals and the review UI)
 * @param field    affected field of {@link dev.belegagent.core.Beleg}, e.g. {@code taxLines[0].tax}
 * @param message  English explanation with the concrete numbers; also sent back to the model for self-correction
 */
public record Finding(String code, Severity severity, String field, String message) {

    static Finding error(String code, String field, String message) {
        return new Finding(code, Severity.ERROR, field, message);
    }

    static Finding warning(String code, String field, String message) {
        return new Finding(code, Severity.WARNING, field, message);
    }
}
