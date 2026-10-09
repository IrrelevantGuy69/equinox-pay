-- Migration V2: Phase 6 (Payment Lifecycle Auth, Capture, Void, Refund) & Phase 7 (Mandates & Recurring Billing)

-- 1. Seed HOLD_SUSPENSE_ACCOUNT in accounts table
INSERT INTO accounts (id, balance)
VALUES ('00000000-0000-0000-0000-000000000000', 0.0000)
ON CONFLICT (id) DO NOTHING;

-- 2. Update ledger_entries type check to include 'REFUND'
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ledger_entries_type_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_type_check CHECK (type IN ('DEBIT', 'CREDIT', 'REFUND'));

-- 3. Update ledger_entries unique constraint to allow multiple entries per transaction if needed or keep balanced type
-- In Phase 6 partial capture / refund, multiple entries may be generated.
-- We drop uq_ledger_transaction_type if it was strictly (transaction_id, type) so partial capture (3 legs) and refunds are supported.
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS uq_ledger_transaction_type;

-- 4. Extend payments table for Phase 6
ALTER TABLE payments ADD COLUMN IF NOT EXISTS authorized_amount DECIMAL(19, 4);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS captured_amount DECIMAL(19, 4);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS refunded_amount DECIMAL(19, 4);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS authorization_expires_at TIMESTAMP WITH TIME ZONE;

-- Index for AutoVoidExpiredAuthorizationsJob
CREATE INDEX IF NOT EXISTS idx_payments_auth_expires ON payments(state, authorization_expires_at);

-- 5. Mandates Table for Phase 7
CREATE TABLE IF NOT EXISTS mandates (
    id UUID PRIMARY KEY,
    payer_account_id UUID NOT NULL REFERENCES accounts(id),
    merchant_account_id UUID NOT NULL REFERENCES accounts(id),
    max_debit_cap DECIMAL(19, 4) NOT NULL CHECK (max_debit_cap > 0),
    frequency VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    last_billed_at TIMESTAMP WITH TIME ZONE,
    next_billing_date TIMESTAMP WITH TIME ZONE,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mandates_status_next_billing ON mandates(status, next_billing_date);
