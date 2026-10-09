-- Equinox Pay - Schema Initialization (Phases 1-5)

-- 1. Accounts Table (The Core Vault)
CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY,
    balance DECIMAL(19, 4) NOT NULL DEFAULT 0.0000 CHECK (balance >= 0)
);

-- 2. Double-Entry Ledger Table
CREATE TABLE IF NOT EXISTS ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id VARCHAR(255) NOT NULL,
    account_id UUID NOT NULL,
    amount DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    type VARCHAR(10) NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uq_ledger_transaction_type UNIQUE (transaction_id, type)
);
CREATE INDEX IF NOT EXISTS idx_ledger_transaction_id ON ledger_entries(transaction_id);

-- 3. Payments Table (State Machine & Concurrency)
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    from_account_id UUID NOT NULL REFERENCES accounts(id),
    to_account_id UUID NOT NULL REFERENCES accounts(id),
    amount DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    state VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_payments_state_updated ON payments(state, updated_at);

-- 4. Payment History (Append-Only Audit Trail)
CREATE TABLE IF NOT EXISTS payment_history (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    previous_state VARCHAR(50),
    new_state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_payment_history_payment_id ON payment_history(payment_id);

-- 5. Users Table (RBAC & Authentication)
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    api_key VARCHAR(255) UNIQUE,
    account_id UUID UNIQUE REFERENCES accounts(id),
    webhook_url VARCHAR(1000),
    webhook_secret VARCHAR(255)
);

-- 6. Refresh Tokens Table (JWT Asymmetric Authentication Lifecycle)
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    expiry_date TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 7. Outbox Events Table (Transactional Outbox Pattern)
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_events_created_at ON outbox_events(created_at ASC);

-- 8. Invoices Table (Merchant Invoicing Engine)
CREATE TABLE IF NOT EXISTS invoices (
    id UUID PRIMARY KEY,
    slug VARCHAR(255) NOT NULL UNIQUE,
    merchant_account_id UUID NOT NULL REFERENCES accounts(id),
    customer_account_id UUID REFERENCES accounts(id),
    amount DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_invoices_slug ON invoices(slug);

-- 9. Failed Webhooks Table (Merchant Webhook DLQ)
CREATE TABLE IF NOT EXISTS failed_webhooks (
    id UUID PRIMARY KEY,
    merchant_account_id UUID NOT NULL REFERENCES accounts(id),
    payload TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_failed_webhooks_retry ON failed_webhooks(status, next_retry_at);

-- Initial Core Vault Accounts
INSERT INTO accounts (id, balance)
VALUES ('11111111-1111-1111-1111-111111111111', 1000.0000)
ON CONFLICT (id) DO NOTHING;

INSERT INTO accounts (id, balance)
VALUES ('22222222-2222-2222-2222-222222222222', 500.0000)
ON CONFLICT (id) DO NOTHING;