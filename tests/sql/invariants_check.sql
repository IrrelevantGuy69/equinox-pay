-- ==============================================================================
-- EQUINOX PAY - CORE FINANCIAL INVARIANTS AUDIT SCRIPT
-- ==============================================================================

-- 1. Invariant 1: Total Debits == Total Credits (Zero-Sum Ledger Rule)
SELECT 
    COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount ELSE 0 END), 0) AS total_debits,
    COALESCE(SUM(CASE WHEN type IN ('CREDIT', 'REFUND') THEN amount ELSE 0 END), 0) AS total_credits,
    (COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount ELSE 0 END), 0) -
     COALESCE(SUM(CASE WHEN type IN ('CREDIT', 'REFUND') THEN amount ELSE 0 END), 0)) AS ledger_drift,
    CASE 
        WHEN (COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount ELSE 0 END), 0) -
              COALESCE(SUM(CASE WHEN type IN ('CREDIT', 'REFUND') THEN amount ELSE 0 END), 0)) = 0 
        THEN 'BALANCED (ZERO DRIFT)' 
        ELSE 'VIOLATION DETECTED' 
    END AS invariant_1_status
FROM ledger_entries;

-- 2. Invariant 2: No Negative Balances in Non-Genesis Accounts
SELECT 
    COUNT(*) AS negative_balance_accounts_count,
    CASE 
        WHEN COUNT(*) = 0 THEN 'NO NEGATIVE BALANCES (PASSED)' 
        ELSE 'VIOLATION: NEGATIVE BALANCE FOUND' 
    END AS invariant_2_status
FROM accounts
WHERE balance < 0 OR available_balance < 0 OR pending_balance < 0;

-- 3. Invariant 3: Split Balances Integrity (balance = available_balance + pending_balance)
SELECT 
    COUNT(*) AS desynchronized_balance_accounts,
    CASE 
        WHEN COUNT(*) = 0 THEN 'ALL BALANCES SYNCHRONIZED (PASSED)' 
        ELSE 'VIOLATION: SPLIT BALANCE DESYNC' 
    END AS invariant_3_status
FROM accounts
WHERE balance <> (available_balance + pending_balance);

-- 4. Invariant 4: Outbox Lag and DLQ Status
SELECT 
    (SELECT COUNT(*) FROM outbox_events) AS pending_outbox_events,
    (SELECT COUNT(*) FROM failed_webhooks WHERE status = 'FAILED_PERMANENTLY') AS poison_webhooks_count;

-- 5. Invariant 5: Reconciliation Dispute Tally
SELECT 
    status, 
    COUNT(*) AS count, 
    COALESCE(SUM(discrepancy_difference), 0) AS total_discrepancy_amount
FROM reconciliation_disputes
GROUP BY status;
