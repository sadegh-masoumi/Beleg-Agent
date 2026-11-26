package dev.belegagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.belegagent.app.intake.Document;
import dev.belegagent.app.intake.DocumentQueue;
import dev.belegagent.app.intake.DocumentStatus;
import dev.belegagent.app.intake.Documents;
import dev.belegagent.app.processing.ModelRateLimiter;
import dev.belegagent.extraction.ExtractionException;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The PostgreSQL job queue and the shared rate limiter against a real database (api role: no worker polling). */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class QueueAndRateLimitTests {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    Documents documents;

    @Autowired
    DocumentQueue queue;

    @Autowired
    DataSource dataSource;

    @Test
    void onlyTheLeaseHolderCanCompleteADocument() {
        UUID id = register("lease-owner");
        Document claimed = claimOwn(id, "worker-a");

        assertThat(claimed.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(claimed.lockedBy()).isEqualTo("worker-a");
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(queue.complete(id, "worker-b", DocumentStatus.ACCEPTED)).isFalse();
        assertThat(queue.complete(id, "worker-a", DocumentStatus.ACCEPTED)).isTrue();
        assertThat(status(id)).isEqualTo(DocumentStatus.ACCEPTED);
    }

    @Test
    void retryIsNotClaimableBeforeItsTime() {
        UUID id = register("retry-later");
        claimOwn(id, "worker-a");

        assertThat(queue.retryLater(id, "worker-a", "HTTP 503", Duration.ofMinutes(10))).isTrue();

        assertThat(queue.claim("worker-b", 1000, LEASE)).extracting(Document::id).doesNotContain(id);
        Document waiting = documents.find("t-01", id).orElseThrow();
        assertThat(waiting.status()).isEqualTo(DocumentStatus.RECEIVED);
        assertThat(waiting.lastError()).isEqualTo("HTTP 503");
        assertThat(waiting.lockedBy()).isNull();
    }

    @Test
    void expiredLeaseReturnsTheDocumentOrDeadLettersIt() throws InterruptedException {
        UUID again = register("lease-expired-retry");
        claimOwn(again, "crashed-worker", Duration.ofMillis(1));
        UUID dead = register("lease-expired-dead");
        claimOwn(dead, "crashed-worker", Duration.ofMillis(1));
        Thread.sleep(50);
        // simulate an exhausted attempt budget for one of them
        JdbcClient.create(dataSource).sql("UPDATE document SET attempts = 5 WHERE id = :id").param("id", dead).update();

        assertThat(queue.releaseExpiredLeases(5)).isGreaterThanOrEqualTo(2);

        Document released = documents.find("t-01", again).orElseThrow();
        assertThat(released.status()).isEqualTo(DocumentStatus.RECEIVED);
        assertThat(released.lastError()).contains("crashed-worker", "expired");
        assertThat(status(dead)).isEqualTo(DocumentStatus.DEAD_LETTER);
        // the crashed worker can no longer store a result
        assertThat(queue.complete(again, "crashed-worker", DocumentStatus.ACCEPTED)).isFalse();
    }

    @Test
    void concurrentWorkersNeverClaimTheSameDocument() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(register("concurrent-" + i));
        }
        List<Callable<List<Document>>> workers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String worker = "worker-" + w;
            workers.add(() -> {
                List<Document> mine = new ArrayList<>();
                List<Document> batch;
                while (!(batch = queue.claim(worker, 3, LEASE)).isEmpty()) {
                    mine.addAll(batch);
                }
                return mine;
            });
        }
        Set<UUID> seen = new HashSet<>();
        int total = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<List<Document>> f : pool.invokeAll(workers)) {
                for (Document d : f.get()) {
                    seen.add(d.id());
                    total++;
                }
            }
        }

        assertThat(total).isEqualTo(seen.size());
        assertThat(seen).containsAll(ids);
    }

    @Test
    void requeueGivesAFreshAttemptBudget() {
        UUID id = register("requeue");
        claimOwn(id, "worker-a");
        queue.fail(id, "worker-a", "gave up", DocumentStatus.DEAD_LETTER);

        assertThat(queue.requeue("t-01", id)).isTrue();

        Document requeued = documents.find("t-01", id).orElseThrow();
        assertThat(requeued.status()).isEqualTo(DocumentStatus.RECEIVED);
        assertThat(requeued.attempts()).isZero();
        assertThat(queue.requeue("t-01", id)).as("only FAILED or DEAD_LETTER").isFalse();
    }

    @Test
    void rateLimitIsSharedByAllWorkers() {
        // 600/min: burst of 60, then one token every 100 ms
        String model = "test-model-" + UUID.randomUUID();
        ModelRateLimiter workerA = new ModelRateLimiter(buckets(), Duration.ofSeconds(5), new SimpleMeterRegistry());
        ModelRateLimiter workerB = new ModelRateLimiter(buckets(), Duration.ofMillis(10), new SimpleMeterRegistry());

        long start = System.nanoTime();
        for (int i = 0; i < 60; i++) {
            workerA.acquire(model, 600);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

        assertThatThrownBy(() -> workerB.acquire(model, 600))
                .isInstanceOfSatisfying(ExtractionException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.retryAfter()).isPositive();
                });

        long waitStart = System.nanoTime();
        workerA.acquire(model, 600);
        assertThat(Duration.ofNanos(System.nanoTime() - waitStart)).isGreaterThan(Duration.ofMillis(20));
    }

    @Test
    void zeroMeansUnlimited() {
        ModelRateLimiter limiter = new ModelRateLimiter(buckets(), Duration.ZERO, new SimpleMeterRegistry());
        for (int i = 0; i < 1000; i++) {
            limiter.acquire("unlimited", 0);
        }
    }

    private ProxyManager<String> buckets() {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .idColumn("id")
                .stateColumn("state")
                .expiresAtColumn("expires_at")
                .build();
    }

    private UUID register(String payload) {
        return documents.register("t-01", payload + ".png", DocumentApiTests.png("queue-" + payload + "-" + UUID.randomUUID()))
                .document().id();
    }

    private Document claimOwn(UUID id, String worker) {
        return claimOwn(id, worker, LEASE);
    }

    /** Claims until the given document is among the claimed ones (other test classes may share the queue). */
    private Document claimOwn(UUID id, String worker, Duration lease) {
        for (int i = 0; i < 100; i++) {
            for (Document d : queue.claim(worker, 50, lease)) {
                if (d.id().equals(id)) {
                    return d;
                }
            }
        }
        throw new AssertionError("document " + id + " was not claimable");
    }

    private DocumentStatus status(UUID id) {
        return documents.find("t-01", id).orElseThrow().status();
    }
}
