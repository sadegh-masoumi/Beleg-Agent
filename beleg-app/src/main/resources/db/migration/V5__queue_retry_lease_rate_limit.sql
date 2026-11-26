-- Phase 4: retry with backoff, leases for crashed workers, dead-letter, cluster-wide model rate limit.

ALTER TABLE document DROP CONSTRAINT document_status_check;
ALTER TABLE document ADD CONSTRAINT document_status_check
    CHECK (status IN ('RECEIVED', 'PROCESSING', 'EXTRACTED', 'ACCEPTED', 'NEEDS_REVIEW', 'APPROVED', 'FAILED', 'DEAD_LETTER'));

ALTER TABLE document
    -- a RECEIVED row is claimable once next_attempt_at has passed (retries are scheduled into the future)
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- lease of the worker processing the row; an expired lease means the worker died
    ADD COLUMN locked_by       TEXT,
    ADD COLUMN locked_until    TIMESTAMPTZ;

UPDATE document SET next_attempt_at = created_at;

DROP INDEX document_received_idx;
CREATE INDEX document_ready_idx ON document (next_attempt_at) WHERE status = 'RECEIVED';
CREATE INDEX document_lease_idx ON document (locked_until) WHERE status = 'PROCESSING';

-- Bucket4j token buckets (one row per model), shared by all workers.
CREATE TABLE rate_limit_bucket (
    id         TEXT   PRIMARY KEY,
    state      BYTEA,
    expires_at BIGINT
);
