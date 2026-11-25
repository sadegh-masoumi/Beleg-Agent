package dev.belegagent.extraction.agent;

import dev.belegagent.core.validation.ValidationResult;
import dev.belegagent.extraction.ExtractionOutcome;

/**
 * One extraction attempt and its validation.
 *
 * @param outcome extracted data plus model, tokens and latency; for {@link Source#E_INVOICE} the model is
 *                {@code e-invoice/<profile>}, there are no tokens and no pages were sent
 */
public record Attempt(Source source, ExtractionOutcome outcome, ValidationResult validation) {
}
