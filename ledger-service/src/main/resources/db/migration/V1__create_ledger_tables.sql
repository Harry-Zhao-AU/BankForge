CREATE TABLE ledger_entries (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    transfer_id     UUID            NOT NULL,
    account_id      UUID            NOT NULL,
    entry_type      VARCHAR(10)     NOT NULL,
    amount          DECIMAL(15,4)   NOT NULL,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'AUD',
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_ledger_entries_transfer ON ledger_entries(transfer_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries(account_id);

COMMENT ON COLUMN ledger_entries.entry_type IS 'DEBIT or CREDIT — double-entry bookkeeping';
