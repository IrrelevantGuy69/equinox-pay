-- Migration V4: Phase 9 (Daily Central Bank CSV Reconciliation, Three-Way Discrepancy Detector & Ledger Invariant Audit)

-- 1. Reconciliation Batches
CREATE TABLE IF NOT EXISTS reconciliation_batches (
    id UUID PRIMARY KEY,
    batch_reference VARCHAR(100) NOT NULL UNIQUE,
    source_file_name VARCHAR(255),
    total_bank_records INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    discrepancy_count INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    ledger_debits_credits_balanced BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_batches_status ON reconciliation_batches(status);

-- 2. Reconciliation Disputes
CREATE TABLE IF NOT EXISTS reconciliation_disputes (
    id UUID PRIMARY KEY,
    reconciliation_batch_id UUID NOT NULL REFERENCES reconciliation_batches(id),
    payment_id UUID REFERENCES payments(id),
    bank_reference VARCHAR(100),
    discrepancy_type VARCHAR(50) NOT NULL,
    expected_amount DECIMAL(19, 4),
    actual_bank_amount DECIMAL(19, 4),
    discrepancy_difference DECIMAL(19, 4),
    status VARCHAR(50) NOT NULL,
    resolution_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_disputes_batch ON reconciliation_disputes(reconciliation_batch_id);
CREATE INDEX IF NOT EXISTS idx_reconciliation_disputes_payment ON reconciliation_disputes(payment_id);
CREATE INDEX IF NOT EXISTS idx_reconciliation_disputes_type ON reconciliation_disputes(discrepancy_type);
