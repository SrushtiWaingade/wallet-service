CREATE TABLE wallets (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       TEXT   NOT NULL UNIQUE,
    balance_paise BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT wallets_balance_non_negative CHECK (balance_paise >= 0)
);

CREATE TABLE transfers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key TEXT   NOT NULL UNIQUE,
    request_hash    TEXT   NOT NULL,
    from_wallet_id  UUID   NOT NULL REFERENCES wallets(id),
    to_wallet_id    UUID   NOT NULL REFERENCES wallets(id),
    amount_paise    BIGINT NOT NULL,
    status          TEXT   NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id),
    CONSTRAINT transfers_status_valid
        CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED_INSUFFICIENT_FUNDS'))
);

-- Every money movement writes two rows summing to zero, so conservation is
-- checkable directly: SELECT sum(delta_paise) FROM ledger_entries;
-- transfer_id is null for the opening balance a wallet draws from the treasury.
CREATE TABLE ledger_entries (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID   REFERENCES transfers(id),
    wallet_id   UUID   NOT NULL REFERENCES wallets(id),
    delta_paise BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_delta_non_zero CHECK (delta_paise <> 0)
);

CREATE INDEX idx_ledger_entries_wallet ON ledger_entries (wallet_id);
CREATE INDEX idx_ledger_entries_transfer ON ledger_entries (transfer_id);

-- Opening balances are moved out of this wallet rather than conjured, which is
-- what lets the service claim total balance never changes, without exceptions.
INSERT INTO wallets (id, user_id, balance_paise)
VALUES ('00000000-0000-0000-0000-000000000001', 'treasury', 1000000000000000);