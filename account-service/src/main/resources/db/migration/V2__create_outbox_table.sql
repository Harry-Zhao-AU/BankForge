-- V2: Create Debezium-compatible outbox table
-- Column names must be lowercase: aggregatetype, aggregateid, type
-- These are the names the Debezium EventRouter SMT expects on the CDC stream in Phase 1.1.
-- payload is JSONB to enable PostgreSQL JSONB operators for per-account transfer history queries.

CREATE TABLE outbox_event (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    aggregatetype   VARCHAR(255)    NOT NULL,
    aggregateid     VARCHAR(255)    NOT NULL,
    type            VARCHAR(255)    NOT NULL,
    payload         JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- Index for transfer history queries: filter by account ID in JSONB payload
CREATE INDEX idx_outbox_aggregatetype ON outbox_event(aggregatetype);
CREATE INDEX idx_outbox_aggregateid ON outbox_event(aggregateid);
