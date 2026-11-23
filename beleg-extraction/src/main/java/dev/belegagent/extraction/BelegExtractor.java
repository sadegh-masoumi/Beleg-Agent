package dev.belegagent.extraction;

/**
 * Extracts structured data from one document. Implemented by {@link BelegExtractionService};
 * tests substitute a fake so the pipeline can run without a model.
 */
@FunctionalInterface
public interface BelegExtractor {

    ExtractionOutcome extract(byte[] content, FileKind kind);
}
