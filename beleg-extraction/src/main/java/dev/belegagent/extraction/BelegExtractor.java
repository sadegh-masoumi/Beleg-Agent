package dev.belegagent.extraction;

import dev.belegagent.core.validation.Finding;
import java.util.List;

/**
 * Extracts structured data from one document with a vision model. Implemented by {@link BelegExtractionService};
 * tests substitute a fake so the pipeline can run without a model.
 */
public interface BelegExtractor {

    /** First attempt. */
    ExtractionOutcome extract(byte[] content, FileKind kind);

    /**
     * Self-correction: the same document again, together with the previous answer and the validator's errors.
     * The model is told to fix misreadings only, never to adjust values to make the checks pass.
     */
    ExtractionOutcome correct(byte[] content, FileKind kind, ExtractionOutcome previous, List<Finding> errors);
}
