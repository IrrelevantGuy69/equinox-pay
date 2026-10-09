-- Migration V3: Phase 8 (Merchant Settlement, T+1 Clearing, Fee Engine & Multi-Leg Split Payments)

-- 1. Add split balance columns to accounts table
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS pending_balance DECIMAL(19, 4) NOT NULL DEFAULT 0.0000;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS available_balance DECIMAL(19, 4) NOT NULL DEFAULT 0.0000;

-- Backfill available_balance for existing non-zero accounts
UPDATE accounts SET available_balance = balance WHERE available_balance = 0.0000 AND balance > 0.0000;

-- 2. Seed reserved system accounts
-- PLATFORM_FEES_ACCOUNT: 11111111-1111-1111-1111-111111111111
-- PAYOUT_TRANSIT_ACCOUNT: 22222222-2222-2222-2222-222222222222
INSERT INTO accounts (id, balance, pending_balance, available_balance)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 0.0000, 0.0000, 0.0000),
    ('22222222-2222-2222-2222-222222222222', 0.0000, 0.0000, 0.0000)
ON CONFLICT (id) DO NOTHING;

-- 3. Extend payments table for fees, capture & clearing metadata
ALTER TABLE payments ADD COLUMN IF NOT EXISTS fee_amount DECIMAL(19, 4);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS net_amount DECIMAL(19, 4);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS captured_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_payments_clearing ON payments(state, cleared_at, captured_at);

-- 4. Merchant Fee Plans
CREATE TABLE IF NOT EXISTS merchant_fee_plans (
    id UUID PRIMARY KEY,
    merchant_account_id UUID NOT NULL UNIQUE REFERENCES accounts(id),
    percentage_rate DECIMAL(10, 4) NOT NULL DEFAULT 0.0000,
    fixed_fee DECIMAL(19, 4) NOT NULL DEFAULT 0.0000,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 5. Settlement Batches
CREATE TABLE IF NOT EXISTS settlement_batches (
    id UUID PRIMARY KEY,
    batch_reference VARCHAR(100) NOT NULL UNIQUE,
    merchant_account_id UUID NOT NULL REFERENCES accounts(id),
    total_amount DECIMAL(19, 4) NOT NULL,
    total_fees DECIMAL(19, 4) NOT NULL,
    net_payout_amount DECIMAL(19, 4) NOT NULL,
    status VARCHAR(50) NOT NULL,
    cleared_at TIMESTAMP WITH TIME ZONE,
    csv_statement TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_settlement_batches_merchant_status ON settlement_batches(merchant_account_id, status);

-- 6. Settlement Items
CREATE TABLE IF NOT EXISTS settlement_items (
    id UUID PRIMARY KEY,
    settlement_batch_id UUID NOT NULL REFERENCES settlement_batches(id),
    payment_id UUID NOT NULL REFERENCES payments(id),
    gross_amount DECIMAL(19, 4) NOT NULL,
    fee_amount DECIMAL(19, 4) NOT NULL,
    net_amount DECIMAL(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_settlement_items_batch ON settlement_items(settlement_batch_id);
CREATE INDEX IF NOT EXISTS idx_settlement_items_payment ON settlement_items(payment_id);
