package dev.belegagent.app.intake;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The job queue on the {@code document} table (ADR 0002, ADR 0006).
 *
 * <ul>
 *   <li><b>Claim:</b> {@code FOR UPDATE SKIP LOCKED} over RECEIVED rows whose {@code next_attempt_at} has passed;
 *       the claiming worker gets a lease ({@code locked_by}, {@code locked_until}).</li>
 *   <li><b>Complete / retry / fail:</b> only the lease holder may change the row, so a worker whose lease expired
 *       (and whose document another worker took over) cannot overwrite the newer result.</li>
 *   <li><b>Lease expiry:</b> rows of a crashed worker go back to RECEIVED, or to DEAD_LETTER once the attempt limit is reached.</li>
 * </ul>
 * Every claim counts as an attempt, including one interrupted by a crash.
 */
@Component
public class DocumentQueue {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;

    public DocumentQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims up to {@code limit} ready documents, oldest due first. */
    public List<Document> claim(String workerId, int limit, Duration lease) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        UPDATE document
                           SET status = 'PROCESSING', attempts = attempts + 1, locked_by = :worker,
                               locked_until = now() + make_interval(secs => :leaseSeconds), updated_at = now()
                         WHERE id IN (SELECT id FROM document
                                       WHERE status = 'RECEIVED' AND next_attempt_at <= now()
                                       ORDER BY next_attempt_at
                                       LIMIT :limit
                                       FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .param("worker", workerId)
                .param("leaseSeconds", lease.toMillis() / 1000.0)
                .param("limit", limit)
                .query(Document.class)
                .list();
    }

    /**
     * Stores the agent's decision ({@link DocumentStatus#ACCEPTED} or {@link DocumentStatus#NEEDS_REVIEW}).
     *
     * @return {@code false} if this worker no longer holds the lease; the caller must then discard its result
     */
    public boolean complete(UUID id, String workerId, DocumentStatus status) {
        if (status != DocumentStatus.ACCEPTED && status != DocumentStatus.NEEDS_REVIEW) {
            throw new IllegalArgumentException("Not a processing outcome: " + status);
        }
        return jdbc.sql("""
                        UPDATE document
                           SET status = :status, last_error = NULL, locked_by = NULL, locked_until = NULL, updated_at = now()
                         WHERE id = :id AND locked_by = :worker AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("worker", workerId)
                .param("status", status.name())
                .update() == 1;
    }

    /** Transient failure: back to RECEIVED, claimable again after {@code delay}. */
    public boolean retryLater(UUID id, String workerId, String error, Duration delay) {
        return jdbc.sql("""
                        UPDATE document
                           SET status = 'RECEIVED', last_error = :error, locked_by = NULL, locked_until = NULL,
                               next_attempt_at = now() + make_interval(secs => :delaySeconds), updated_at = now()
                         WHERE id = :id AND locked_by = :worker AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("worker", workerId)
                .param("error", truncate(error))
                .param("delaySeconds", delay.toMillis() / 1000.0)
                .update() == 1;
    }

    /** Gives up: {@link DocumentStatus#FAILED} (permanent error) or {@link DocumentStatus#DEAD_LETTER} (retries exhausted). */
    public boolean fail(UUID id, String workerId, String error, DocumentStatus status) {
        if (status != DocumentStatus.FAILED && status != DocumentStatus.DEAD_LETTER) {
            throw new IllegalArgumentException("Not a failure status: " + status);
        }
        return jdbc.sql("""
                        UPDATE document
                           SET status = :status, last_error = :error, locked_by = NULL, locked_until = NULL, updated_at = now()
                         WHERE id = :id AND locked_by = :worker AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("worker", workerId)
                .param("status", status.name())
                .param("error", truncate(error))
                .update() == 1;
    }

    /**
     * Releases leases of crashed or stuck workers: back to RECEIVED, or DEAD_LETTER once {@code maxAttempts} is reached.
     *
     * @return number of released rows
     */
    public int releaseExpiredLeases(int maxAttempts) {
        return jdbc.sql("""
                        UPDATE document
                           SET status = CASE WHEN attempts >= :maxAttempts THEN 'DEAD_LETTER' ELSE 'RECEIVED' END,
                               last_error = 'Lease of worker ' || coalesce(locked_by, '?') || ' expired',
                               locked_by = NULL, locked_until = NULL, next_attempt_at = now(), updated_at = now()
                         WHERE status = 'PROCESSING' AND locked_until < now()
                        """)
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /** Operator action: put a FAILED or DEAD_LETTER document back into the queue with a fresh attempt budget. */
    public boolean requeue(String tenantId, UUID id) {
        return jdbc.sql("""
                        UPDATE document
                           SET status = 'RECEIVED', attempts = 0, next_attempt_at = now(), updated_at = now()
                         WHERE id = :id AND tenant_id = :tenant AND status IN ('FAILED', 'DEAD_LETTER')
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .update() == 1;
    }

    /** Number of documents per status (all tenants), for the queue-depth metric. */
    public Map<DocumentStatus, Long> countByStatus() {
        Map<DocumentStatus, Long> counts = new EnumMap<>(DocumentStatus.class);
        for (DocumentStatus s : DocumentStatus.values()) {
            counts.put(s, 0L);
        }
        jdbc.sql("SELECT status, count(*) AS n FROM document GROUP BY status")
                .query((rs, rowNum) -> counts.put(DocumentStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list();
        return counts;
    }

    private static String truncate(String error) {
        String message = error == null ? "unknown error" : error;
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
