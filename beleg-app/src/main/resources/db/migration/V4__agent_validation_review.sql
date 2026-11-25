-- Phase 3: agent run (routing, validation, self-correction, account suggestion) and human review.
-- All tables stay append-only (GoBD): a correction is a new row, the model output is never changed.

-- New statuses: the agent decides ACCEPTED or NEEDS_REVIEW, a human sets APPROVED.
-- EXTRACTED remains for documents processed before Phase 3.
ALTER TABLE document DROP CONSTRAINT document_status_check;
ALTER TABLE document ADD CONSTRAINT document_status_check
    CHECK (status IN ('RECEIVED', 'PROCESSING', 'EXTRACTED', 'ACCEPTED', 'NEEDS_REVIEW', 'APPROVED', 'FAILED'));

CREATE INDEX document_needs_review_idx ON document (tenant_id, created_at) WHERE status = 'NEEDS_REVIEW';

-- One row per agent run over a document.
CREATE TABLE processing_run (
    id           UUID        PRIMARY KEY,
    document_id  UUID        NOT NULL REFERENCES document (id),
    decision     TEXT        NOT NULL CHECK (decision IN ('ACCEPTED', 'NEEDS_REVIEW')),
    -- SKR03/SKR04 suggestion for the chosen attempt
    account      JSONB       NOT NULL,
    -- routing and fallback notes
    notes        JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX processing_run_document_idx ON processing_run (document_id, created_at DESC);

-- Every attempt of a run is an extraction row; exactly one per run is the chosen one.
ALTER TABLE extraction
    ADD COLUMN run_id     UUID REFERENCES processing_run (id),
    ADD COLUMN source     TEXT    NOT NULL DEFAULT 'MODEL' CHECK (source IN ('E_INVOICE', 'MODEL', 'MODEL_CORRECTION')),
    ADD COLUMN attempt    INT     NOT NULL DEFAULT 1,
    ADD COLUMN chosen     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN valid      BOOLEAN,
    ADD COLUMN validation JSONB;

-- Human decisions. APPROVE keeps the chosen extraction, CORRECT stores the corrected data next to it.
CREATE TABLE review (
    id             UUID        PRIMARY KEY,
    document_id    UUID        NOT NULL REFERENCES document (id),
    extraction_id  UUID        NOT NULL REFERENCES extraction (id),
    action         TEXT        NOT NULL CHECK (action IN ('APPROVE', 'CORRECT')),
    beleg          JSONB,
    validation     JSONB,
    account        TEXT,
    reviewer       TEXT        NOT NULL,
    comment        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((action = 'CORRECT') = (beleg IS NOT NULL))
);

CREATE INDEX review_document_idx ON review (document_id, created_at DESC);
