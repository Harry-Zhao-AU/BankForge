-- Outbox table for Debezium CDC publication of banking.transfer.confirmed
-- Column names MUST match Debezium EventRouter SMT expectations exactly
CREATE TABLE ledger_outbox_event (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    aggregatetype   VARCHAR(255)    NOT NULL,
    aggregateid     VARCHAR(255)    NOT NULL,
    type            VARCHAR(255)    NOT NULL,
    payload         JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

COMMENT ON TABLE ledger_outbox_event IS 'Transactional outbox for Debezium CDC -- publishes banking.transfer.confirmed';
COMMENT ON COLUMN ledger_outbox_event.aggregatetype IS 'Fixed value: transfer-confirmation';
COMMENT ON COLUMN ledger_outbox_event.aggregateid IS 'transferId -- becomes Kafka record key AND transaction-id header (via Debezium EventRouter additional.placement)';
