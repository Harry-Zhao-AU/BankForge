-- V1: Create transfers table
-- Amount uses DECIMAL(15,4) per D-09 (IEEE 754 rounding causes cent errors with float/double)
-- State is VARCHAR(32) to store TransferState enum name
-- idempotency_key has a UNIQUE index to prevent DB-level duplicates even if Redis is unavailable (T-1-06)

CREATE TABLE transfers (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    from_account_id     UUID            NOT NULL,
    to_account_id       UUID            NOT NULL,
    amount              DECIMAL(15,4)   NOT NULL,
    state               VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    idempotency_key     VARCHAR(255)    NOT NULL,
    description         VARCHAR(500),
    error_message       VARCHAR(500),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_transfers_idempotency ON transfers(idempotency_key);
