package dev.belegagent.app.processing;

import dev.belegagent.app.intake.Document;
import dev.belegagent.app.intake.DocumentStatus;
import dev.belegagent.app.intake.Documents;
import dev.belegagent.extraction.FileKind;
import dev.belegagent.extraction.agent.AgentResult;
import dev.belegagent.extraction.agent.BelegAgent;
import dev.belegagent.extraction.agent.Decision;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the PostgreSQL job queue and runs the {@link BelegAgent} on each claimed document
 * (route → extract → validate → self-correct once → decide → suggest account).
 * One document at a time per worker, no retry yet (a failure marks the document FAILED).
 * Retry with backoff, dead-letter, rate limiting and concurrency limits follow in Phase 4.
 */
@Component
@Profile("worker")
class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final Documents documents;
    private final Extractions extractions;
    private final BelegAgent agent;
    private final TransactionTemplate tx;

    ExtractionWorker(Documents documents, Extractions extractions, BelegAgent agent, TransactionTemplate tx) {
        this.documents = documents;
        this.extractions = extractions;
        this.agent = agent;
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
        log.info("Processing document {} ({}, {} bytes)", document.id(), document.contentType(), document.sizeBytes());
        try {
            FileKind kind = FileKind.fromMimeType(document.contentType())
                    .orElseThrow(() -> new IllegalStateException("Unsupported content type " + document.contentType()));
            AgentResult result = agent.process(documents.content(document), kind);
            DocumentStatus status = result.decision() == Decision.ACCEPTED
                    ? DocumentStatus.ACCEPTED : DocumentStatus.NEEDS_REVIEW;
            tx.executeWithoutResult(s -> {
                extractions.saveRun(document.id(), result);
                documents.markProcessed(document.id(), status);
            });
            log.info("Document {}: {} via {} after {} attempt(s), {} error(s), account {} ({})", document.id(), status,
                    result.chosenAttempt().outcome().model(), result.attempts().size(),
                    result.chosenAttempt().validation().errors().size(), result.account().account(),
                    result.account().confidence());
        } catch (RuntimeException e) {
            log.warn("Processing of document {} failed: {}", document.id(), e.getMessage());
            documents.markFailed(document.id(), e.getMessage());
        }
    }
}
