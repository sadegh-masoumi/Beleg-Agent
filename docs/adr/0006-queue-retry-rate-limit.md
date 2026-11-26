# ADR 0006: Queue semantics, retry, dead-letter and cluster-wide rate limit

- Status: accepted (refines [ADR 0002](0002-postgres-queue.md))
- Date: 2026-11-25

## Context
Phase 4 makes processing robust and measurable under load. The model API is slow (seconds per call), rate-limited
(requests per minute per key) and sometimes overloaded (HTTP 503 "high demand" on 2026-11-23). Workers can crash mid-document.
Several workers share one API key.

## Decision
1. **The `document` row is the job.** Upload writes the row in one statement; there is no separate outbox table or broker.
   ADR 0002's outbox is only needed once jobs go to an external broker (RabbitMQ); the message contract stays broker-neutral.
2. **Claim with lease:** `UPDATE … WHERE id IN (SELECT … WHERE status = 'RECEIVED' AND next_attempt_at <= now()
   ORDER BY next_attempt_at LIMIT n FOR UPDATE SKIP LOCKED)` sets `PROCESSING`, `attempts + 1`, `locked_by`, `locked_until`.
   A worker claims only as many rows as it has free slots.
3. **Only the lease holder writes:** complete, retry and fail are conditional on `locked_by = me AND status = 'PROCESSING'`.
   Storing the result and the status change happen in one transaction; a worker that lost its lease discards its result.
4. **Expired leases** (crashed worker) are released by any worker's poll: back to `RECEIVED`, or `DEAD_LETTER` when the
   attempt budget is spent. A crash counts as an attempt, so a document that kills its worker cannot loop forever.
5. **Failure classes:**
   - *transient* (429, 5xx, timeouts, network, storage, local rate limit) → retry after
     `uniform(0, min(maxDelay, base × 2^(attempt−1)))` (exponential backoff, **full jitter**), `DEAD_LETTER` after `max-attempts` (5);
   - *permanent* (unreadable PDF, rejected request, bad API key) → `FAILED` at once.
   Both can be requeued by an operator (`POST /api/documents/{id}/requeue`, fresh attempt budget).
6. **No in-call retries in LangChain4j** (`max-retries: 0`): they hold a worker slot, ignore the rate limit and retry in lockstep.
   The queue retries instead.
7. **Self-correction under a transient failure** (e.g. 503 from the strong model) retries the whole document later instead of
   sending it to human review; only on the final attempt does the document go to review with the first answer.
8. **Cluster-wide rate limit per model:** a Bucket4j token bucket in PostgreSQL (`rate_limit_bucket`, `SELECT … FOR UPDATE`),
   requests per minute from `GEMINI_RPM` / `GEMINI_STRONG_RPM`. Burst = 1/10 of the minute's quota, continuous refill.
   A call waits at most 30 s for a token; beyond that the document is requeued with `retryAfter` (backpressure, not blocking).
9. **Concurrency** per worker is a semaphore over virtual threads (`beleg.worker.concurrency`); throughput scales with
   `workers × concurrency / model latency` until the rate limit caps it.
10. **Metrics** (Micrometer, `/actuator/metrics`): `beleg.documents.processed{outcome}`, `beleg.documents.processing`,
    `beleg.model.calls{model,kind,outcome}`, `beleg.model.ratelimit.wait`, `beleg.model.ratelimit.rejected`,
    `beleg.queue.documents{status}` (scale workers on queue depth, not CPU).

## Alternatives
- **Spring Modulith event externalization + RabbitMQ:** the right step when PostgreSQL becomes the bottleneck (see the load test);
  not needed at the measured throughput.
- **Per-JVM rate limiter (Resilience4j):** N workers would send N × the quota.
- **Rate limit in Redis:** one more service for a value PostgreSQL already serves at this rate (one row lock per model call).

## Consequences
- A document is processed at least once; storing is exactly once (lease check + transaction). A model call may be repeated
  after a crash, which costs money but not correctness.
- The token bucket row is a serialization point: every model call takes a row lock. Fine at hundreds of calls per second,
  to be measured before going beyond.
- Queue fairness across tenants is not addressed yet (one large tenant can delay others): next step is a per-tenant bucket or
  weighted claim.
