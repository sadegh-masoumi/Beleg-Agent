package dev.belegagent.app.processing;

import dev.belegagent.app.intake.Document;
import dev.belegagent.app.intake.DocumentQueue;
import dev.belegagent.app.intake.DocumentStatus;
import dev.belegagent.app.intake.Documents;
import dev.belegagent.app.storage.DocumentStorage;
import dev.belegagent.extraction.ExtractionException;
import dev.belegagent.extraction.FileKind;
import dev.belegagent.extraction.agent.AgentResult;
import dev.belegagent.extraction.agent.BelegAgent;
import dev.belegagent.extraction.agent.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the PostgreSQL job queue and runs the {@link BelegAgent} on each claimed document
 * (route → extract → validate → self-correct once → decide → suggest account).
 *
 * <p>Up to {@code beleg.worker.concurrency} documents run in parallel on virtual threads. A poll claims only as many
 * documents as there are free slots, so a busy worker leaves the rest of the queue to other workers.
 * Failures (ADR 0006): transient → retry with exponential backoff and full jitter, DEAD_LETTER after
 * {@code max-attempts}; permanent → FAILED at once. The result is stored only if the worker still holds the lease.
 */
@Component
@Profile("worker")
class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final Documents documents;
    private final DocumentQueue queue;
    private final Extractions extractions;
    private final BelegAgent agent;
    private final TransactionTemplate tx;
    private final WorkerProperties properties;
    private final RetryPolicy retryPolicy;
    private final MeterRegistry registry;
    private final Timer processingTimer;
    private final String workerId = workerId();
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;

    ExtractionWorker(Documents documents, DocumentQueue queue, Extractions extractions, BelegAgent agent,
                     TransactionTemplate tx, WorkerProperties properties, MeterRegistry registry) {
        this.documents = documents;
        this.queue = queue;
        this.extractions = extractions;
        this.agent = agent;
        this.tx = tx;
        this.properties = properties;
        this.retryPolicy = new RetryPolicy(properties.maxAttempts(), properties.retryBaseDelay(),
                properties.retryMaxDelay(), new Random());
        this.registry = registry;
        this.processingTimer = Timer.builder("beleg.documents.processing")
                .description("Time from claim to stored result, per document")
                .register(registry);
        this.slots = new Semaphore(properties.concurrency());
        log.info("Worker {} started: concurrency {}, lease {}, max attempts {}", workerId, properties.concurrency(),
                properties.lease(), properties.maxAttempts());
    }

    @Scheduled(fixedDelayString = "${beleg.worker.poll-interval-ms:500}")
    void poll() {
        if (stopping) {
            return;
        }
        int released = queue.releaseExpiredLeases(properties.maxAttempts());
        if (released > 0) {
            log.warn("Released {} document(s) with an expired lease", released);
        }
        int free = slots.availablePermits();
        if (free == 0) {
            return;
        }
        List<Document> claimed = queue.claim(workerId, free, properties.lease());
        for (Document document : claimed) {
            slots.acquireUninterruptibly();
            executor.execute(() -> {
                try {
                    process(document);
                } finally {
                    slots.release();
                }
            });
        }
    }

    void process(Document document) {
        Timer.Sample sample = Timer.start(registry);
        String outcome;
        try {
            outcome = run(document);
        } catch (RuntimeException e) {
            outcome = handleFailure(document, e);
        }
        sample.stop(processingTimer);
        registry.counter("beleg.documents.processed", "outcome", outcome).increment();
    }

    private String run(Document document) {
        FileKind kind = FileKind.fromMimeType(document.contentType())
                .orElseThrow(() -> new IllegalStateException("Unsupported content type " + document.contentType()));
        boolean finalAttempt = document.attempts() >= properties.maxAttempts();
        AgentResult result = agent.process(documents.content(document), kind, finalAttempt);
        DocumentStatus status = result.decision() == Decision.ACCEPTED ? DocumentStatus.ACCEPTED : DocumentStatus.NEEDS_REVIEW;
        Boolean stored = tx.execute(s -> {
            if (!queue.complete(document.id(), workerId, status)) {
                s.setRollbackOnly();
                return false;
            }
            extractions.saveRun(document.id(), result);
            return true;
        });
        if (!Boolean.TRUE.equals(stored)) {
            log.warn("Document {}: lease lost (another worker took over), result discarded", document.id());
            return "lease_lost";
        }
        log.info("Document {}: {} via {} (attempt {}), {} error(s), account {} ({})", document.id(), status,
                result.chosenAttempt().outcome().model(), document.attempts(),
                result.chosenAttempt().validation().errors().size(), result.account().account(),
                result.account().confidence());
        return status.name().toLowerCase();
    }

    private String handleFailure(Document document, RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!isRetryable(e)) {
            log.warn("Document {} failed permanently: {}", document.id(), message);
            queue.fail(document.id(), workerId, message, DocumentStatus.FAILED);
            return "failed";
        }
        Duration retryAfter = e instanceof ExtractionException ee ? ee.retryAfter() : null;
        Optional<Duration> delay = retryPolicy.delayAfter(document.attempts(), retryAfter);
        if (delay.isEmpty()) {
            log.warn("Document {} dead-lettered after {} attempts: {}", document.id(), document.attempts(), message);
            queue.fail(document.id(), workerId, "Gave up after " + document.attempts() + " attempts: " + message,
                    DocumentStatus.DEAD_LETTER);
            return "dead_letter";
        }
        log.info("Document {} attempt {} failed ({}), retry in {} ms", document.id(), document.attempts(), message,
                delay.get().toMillis());
        queue.retryLater(document.id(), workerId, message, delay.get());
        return "retry";
    }

    static boolean isRetryable(RuntimeException e) {
        if (e instanceof ExtractionException ee) {
            return ee.retryable();
        }
        return e instanceof DocumentStorage.StorageException
                || e instanceof TransientDataAccessException
                || e instanceof DataAccessResourceFailureException;
    }

    /** Stops claiming and gives running documents time to finish; unfinished ones are released when their lease expires. */
    @PreDestroy
    void shutdown() throws InterruptedException {
        stopping = true;
        executor.shutdown();
        if (!executor.awaitTermination(properties.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("Worker {} stopped with documents still running; their leases will expire", workerId);
            executor.shutdownNow();
        }
    }

    private static String workerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
