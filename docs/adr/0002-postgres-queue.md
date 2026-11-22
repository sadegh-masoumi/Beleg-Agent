# ADR 0002: PostgreSQL SKIP LOCKED as the job queue for the MVP

- Status: accepted
- Date: 2026-11-22

## Context
Upload must be decoupled from processing (slow LLM calls, retries, dead-letter).
Target load: 1 M documents/day ≈ 12 msg/s average, ~58 docs/s at a 5× month-end peak.

## Decision
- The API writes the document row and an outbox event in **one transaction** (Spring Modulith Event Publication Registry).
- Jobs are consumed by workers from a PostgreSQL table with `SELECT … FOR UPDATE SKIP LOCKED`.
- Idempotency: unique key on `(tenant_id, content_sha256)`; duplicates return the previous result.
- Retry with exponential backoff and full jitter; after N attempts the job moves to a dead-letter state.

## Alternatives
- **RabbitMQ 4 (quorum queues):** the best fit for slow LLM jobs once PostgreSQL becomes the bottleneck (beyond ~100 concurrent workers, vacuum/bloat pressure). Note: default delivery limit is 20 and dead-lettering must be set to at-least-once.
- **Kafka:** only for export/analytics event streams, not for job dispatch.

## Consequences
- One less moving part in Phase 0–3; the message contract (`jobId, documentId, tenantId, contentSha256, objectKey, attempt`) is kept broker-neutral so the switch to RabbitMQ in Phase 4+ is local to the processing module.
