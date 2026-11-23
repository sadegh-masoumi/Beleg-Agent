-- Phase 1: uploaded documents and extraction results.

CREATE TABLE document (
    id                UUID        PRIMARY KEY,
    tenant_id         TEXT        NOT NULL,
    content_sha256    CHAR(64)    NOT NULL,
    object_key        TEXT        NOT NULL,
    original_filename TEXT,
    content_type      TEXT        NOT NULL,
    size_bytes        BIGINT      NOT NULL,
    status            TEXT        NOT NULL
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'EXTRACTED', 'FAILED')),
    attempts          INT         NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Idempotency: the same file is processed once per tenant.
    CONSTRAINT document_tenant_content_uk UNIQUE (tenant_id, content_sha256)
);

-- Job queue (MVP): workers claim RECEIVED rows with FOR UPDATE SKIP LOCKED.
CREATE INDEX document_received_idx ON document (created_at) WHERE status = 'RECEIVED';

-- Append-only: every model call adds a row; nothing is updated or deleted (GoBD).
CREATE TABLE extraction (
    id            UUID        PRIMARY KEY,
    document_id   UUID        NOT NULL REFERENCES document (id),
    model         TEXT        NOT NULL,
    raw_response  JSONB,
    beleg         JSONB       NOT NULL,
    -- key columns for queries and reports
    seller_name   TEXT,
    issue_date    DATE,
    gross_total   NUMERIC(14, 2),
    currency      TEXT,
    input_tokens  INT,
    output_tokens INT,
    latency_ms    BIGINT      NOT NULL,
    pages_sent    INT         NOT NULL,
    total_pages   INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX extraction_document_idx ON extraction (document_id, created_at DESC);
