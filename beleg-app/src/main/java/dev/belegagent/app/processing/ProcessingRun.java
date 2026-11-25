package dev.belegagent.app.processing;

import dev.belegagent.core.accounting.AccountSuggestion;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One agent run over a document with all its attempts (row of {@code processing_run} plus its extractions). */
public record ProcessingRun(
        UUID id,
        UUID documentId,
        String decision,
        AccountSuggestion account,
        List<String> notes,
        List<Extraction> attempts,
        OffsetDateTime createdAt) {
}
