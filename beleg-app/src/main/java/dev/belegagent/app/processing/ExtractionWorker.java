package dev.belegagent.app.processing;

import dev.belegagent.app.intake.Document;
import dev.belegagent.app.intake.Documents;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the PostgreSQL job queue and extracts each claimed document.
 * Phase 1: one document at a time per worker, no retry (a failure marks the document FAILED).
 * Retry with backoff, dead-letter, rate limiting and concurrency limits follow in Phase 4.
 */
@Component
@Profile("worker")
class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final Documents documents;
    private final Extractions extractions;
    private final BelegExtractor extractor;
    private final TransactionTemplate tx;

    ExtractionWorker(Documents documents, Extractions extractions, BelegExtractor extractor, TransactionTemplate tx) {
        this.documents = documents;
        this.extractions = extractions;
        this.extractor = extractor;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${beleg.worker.poll-interval-ms:1000}")
    void poll() {
        Optional<Document> next;
        while ((next = documents.claimNext()).isPresent()) {
            process(next.get());
        }
    }

    void process(Document document) {
        log.info("Extracting document {} ({}, {} bytes)", document.id(), document.contentType(), document.sizeBytes());
        try {
            FileKind kind = FileKind.fromMimeType(document.contentType())
                    .orElseThrow(() -> new IllegalStateException("Unsupported content type " + document.contentType()));
            ExtractionOutcome outcome = extractor.extract(documents.content(document), kind);
            tx.executeWithoutResult(status -> {
                extractions.save(document.id(), outcome);
                documents.markExtracted(document.id());
            });
            log.info("Extracted document {} with {} in {} ms (tokens in/out: {}/{})", document.id(), outcome.model(),
                    outcome.latency().toMillis(), outcome.inputTokens(), outcome.outputTokens());
        } catch (RuntimeException e) {
            log.warn("Extraction of document {} failed: {}", document.id(), e.getMessage());
            documents.markFailed(document.id(), e.getMessage());
        }
    }
}
