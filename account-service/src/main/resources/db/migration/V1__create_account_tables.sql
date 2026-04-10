-- V1: Create account tables
-- Per D-09: DECIMAL(15,4) for all monetary/balance columns. Never DOUBLE PRECISION or REAL.
-- wal_level=logical is set at the Compose level (compose.yml command: args) — no per-table config needed.

CREATE TABLE accounts (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    bsb             VARCHAR(7)      NOT NULL,
    account_number  VARCHAR(10)     NOT NULL,
    account_name    VARCHAR(100)    NOT NULL,
    balance         DECIMAL(15,4)   NOT NULL DEFAULT 0.0000,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'AUD',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- Unique constraint: no two accounts can share the same BSB + account number combination
CREATE UNIQUE INDEX idx_accounts_bsb_number ON accounts(bsb, account_number);
