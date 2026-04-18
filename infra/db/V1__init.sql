-- Minimal schema for CP-04 QueueDrainer (CockroachDB / PostgreSQL wire protocol).
-- Apply manually or via CP-05 init: `cockroach sql --url "$CRDB_URL" < infra/db/V1__init.sql`

CREATE TABLE IF NOT EXISTS tail_pointer (
    server_id        STRING NOT NULL PRIMARY KEY,
    chronicle_index  BIGINT NOT NULL,
    committed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    entry_id               UUID NOT NULL,
    account_id             UUID NOT NULL,
    correlation_id         UUID NOT NULL,
    end_to_end_id          STRING NOT NULL,
    payment_info_id        STRING NOT NULL,
    debit_credit_indicator STRING NOT NULL CHECK (debit_credit_indicator IN ('CRDT','DBIT')),
    amount                 DECIMAL(19,4) NOT NULL,
    currency               CHAR(3) NOT NULL,
    previous_balance       DECIMAL(19,4) NOT NULL,
    current_balance        DECIMAL(19,4) NOT NULL,
    value_date             DATE NOT NULL,
    booking_date           DATE NOT NULL,
    local_instrument       STRING NOT NULL,
    status                 STRING NOT NULL,
    chronicle_index        BIGINT NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, entry_id)
);

CREATE INDEX IF NOT EXISTS idx_ledger_correlation ON ledger_entry (correlation_id);

CREATE TABLE IF NOT EXISTS ledger_balance (
    balance_id    UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL,
    balance_type  STRING NOT NULL DEFAULT 'CLBD',
    amount        DECIMAL(19,4) NOT NULL,
    currency      CHAR(3) NOT NULL,
    as_of_date    DATE NOT NULL DEFAULT current_date(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, balance_type)
);
