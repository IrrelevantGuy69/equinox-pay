package com.equinox;

import com.equinox.core.entity.*;
import com.equinox.core.repository.*;
import com.equinox.core.service.PaymentEngineService;
import com.equinox.core.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DailyReconciliationPhase9IntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private PaymentEngineService paymentEngineService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private ReconciliationBatchRepository reconciliationBatchRepository;

    @Autowired
    private ReconciliationDisputeRepository reconciliationDisputeRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Account payerAccount;
    private Account merchantAccount;

    @BeforeEach
    void setup() {
        payerAccount = accountRepository.save(Account.builder()
                .balance(new BigDecimal("2000.0000"))
                .availableBalance(new BigDecimal("2000.0000"))
                .pendingBalance(BigDecimal.ZERO)
                .build());

        merchantAccount = accountRepository.save(Account.builder()
                .balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .pendingBalance(BigDecimal.ZERO)
                .build());
    }

    @Test
    @DisplayName("Phase 9 Invariant: Perfect reconciliation run matches 100% of records with zero disputes")
    void testPerfectReconciliationRun() {
        Payment p1 = paymentEngineService.authorizePayment(payerAccount.getId(), merchantAccount.getId(),
                new BigDecimal("100.0000"), Instant.now().plus(1, ChronoUnit.HOURS));
        p1 = paymentEngineService.capturePayment(p1.getId(), new BigDecimal("100.0000"), "cap_p1_" + UUID.randomUUID());

        Payment p2 = paymentEngineService.authorizePayment(payerAccount.getId(), merchantAccount.getId(),
                new BigDecimal("250.0000"), Instant.now().plus(1, ChronoUnit.HOURS));
        p2 = paymentEngineService.capturePayment(p2.getId(), new BigDecimal("250.0000"), "cap_p2_" + UUID.randomUUID());

        String csv = String.format("""
                bank_reference,transaction_id,amount,currency,settled_at,type
                BNK_001,%s,100.0000,USD,2026-10-09T10:00:00Z,SETTLEMENT
                BNK_002,%s,250.0000,USD,2026-10-09T10:00:00Z,SETTLEMENT
                """, p1.getId(), p2.getId());

        ReconciliationBatch batch = reconciliationService.reconcileCsvContent(
                "central_bank_feed_20261009.csv", csv
        );

        assertNotNull(batch);
        assertEquals(ReconciliationBatchStatus.COMPLETED, batch.getStatus());
        assertEquals(2, batch.getTotalBankRecords());
        assertEquals(2, batch.getMatchedCount());
        assertEquals(0, batch.getDiscrepancyCount());
        assertTrue(batch.isLedgerDebitsCreditsBalanced(), "System-wide ledger must be strictly balanced");

        List<ReconciliationDispute> disputes = reconciliationDisputeRepository.findByReconciliationBatchId(batch.getId());
        assertTrue(disputes.isEmpty(), "No disputes should be logged on a clean reconciliation run");
    }

    @Test
    @DisplayName("Phase 9 Invariant: AMOUNT_MISMATCH detects 1-cent variance, freezes payment to DISPUTED")
    void testAmountMismatchDetection() {
        Payment auth = paymentEngineService.authorizePayment(payerAccount.getId(), merchantAccount.getId(),
                new BigDecimal("100.0000"), Instant.now().plus(1, ChronoUnit.HOURS));
        Payment captured = paymentEngineService.capturePayment(auth.getId(), new BigDecimal("100.0000"), "cap_mismatch_" + UUID.randomUUID());

        // Bank reports $99.99 (1 cent missing)
        String csv = String.format("""
                bank_reference,transaction_id,amount,currency,settled_at,type
                BNK_DISCREPANCY,%s,99.9900,USD,2026-10-09T10:00:00Z,SETTLEMENT
                """, captured.getId());

        ReconciliationBatch batch = reconciliationService.reconcileCsvContent(
                "feed_mismatch.csv", csv
        );

        assertEquals(1, batch.getDiscrepancyCount());

        Payment disputedPayment = paymentRepository.findById(captured.getId()).orElseThrow();
        assertEquals(PaymentState.DISPUTED, disputedPayment.getState(), "Payment state must transition to DISPUTED");

        List<ReconciliationDispute> disputes = reconciliationDisputeRepository.findByReconciliationBatchId(batch.getId());
        assertEquals(1, disputes.size());

        ReconciliationDispute dispute = disputes.get(0);
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, dispute.getDiscrepancyType());
        assertEquals(0, new BigDecimal("100.0000").compareTo(dispute.getExpectedAmount()));
        assertEquals(0, new BigDecimal("99.9900").compareTo(dispute.getActualBankAmount()));
        assertEquals(0, new BigDecimal("-0.0100").compareTo(dispute.getDiscrepancyDifference()));

        // Outbox event verification
        boolean eventFound = outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getPayload().contains(captured.getId().toString()) &&
                               e.getPayload().contains("PAYMENT_DISPUTED"));
        assertTrue(eventFound, "PAYMENT_DISPUTED outbox event must be emitted");
    }

    @Test
    @DisplayName("Phase 9 Invariant: MISSING_FUNDS reverse scan catches captured payment absent from bank statement")
    void testMissingFundsDetection() {
        Payment auth = paymentEngineService.authorizePayment(payerAccount.getId(), merchantAccount.getId(),
                new BigDecimal("75.0000"), Instant.now().plus(1, ChronoUnit.HOURS));
        Payment captured = paymentEngineService.capturePayment(auth.getId(), new BigDecimal("75.0000"), "cap_missing_" + UUID.randomUUID());

        // Bank CSV has NO record of captured payment
        String csv = """
                bank_reference,transaction_id,amount,currency,settled_at,type
                """;

        Instant windowStart = captured.getCapturedAt().minusSeconds(5);
        Instant windowEnd = Instant.now().plusSeconds(5);
        ReconciliationBatch batch = reconciliationService.reconcileCsvContent(
                "feed_missing.csv", csv, windowStart, windowEnd
        );

        Payment disputed = paymentRepository.findById(captured.getId()).orElseThrow();
        assertEquals(PaymentState.DISPUTED, disputed.getState(), "Missing payment must be flagged DISPUTED");

        List<ReconciliationDispute> disputes = reconciliationDisputeRepository.findByReconciliationBatchId(batch.getId());
        boolean hasMissingDispute = disputes.stream().anyMatch(d ->
                d.getDiscrepancyType() == DiscrepancyType.MISSING_FUNDS &&
                d.getPayment() != null && d.getPayment().getId().equals(captured.getId()));

        assertTrue(hasMissingDispute, "MISSING_FUNDS dispute must be recorded for payment");
    }

    @Test
    @DisplayName("Phase 9 Invariant: ORPHANED_RECORD records anomaly for unknown bank transaction")
    void testOrphanedRecordDetection() {
        UUID unknownId = UUID.randomUUID();
        String csv = String.format("""
                bank_reference,transaction_id,amount,currency,settled_at,type
                BNK_ORPHAN_999,%s,500.0000,USD,2026-10-09T10:00:00Z,SETTLEMENT
                """, unknownId);

        ReconciliationBatch batch = reconciliationService.reconcileCsvContent(
                "feed_orphan.csv", csv
        );

        assertEquals(1, batch.getDiscrepancyCount());
        List<ReconciliationDispute> disputes = reconciliationDisputeRepository.findByReconciliationBatchId(batch.getId());
        assertEquals(1, disputes.size());

        ReconciliationDispute dispute = disputes.get(0);
        assertEquals(DiscrepancyType.ORPHANED_RECORD, dispute.getDiscrepancyType());
        assertNull(dispute.getPayment());
        assertEquals(0, new BigDecimal("500.0000").compareTo(dispute.getActualBankAmount()));

        boolean eventFound = outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getPayload().contains("RECONCILIATION_ANOMALY_DETECTED"));
        assertTrue(eventFound, "RECONCILIATION_ANOMALY_DETECTED outbox event must be emitted");
    }

    @Test
    @DisplayName("Phase 9 Invariant: System-wide ledger audit mathematically verifies balanced Debits == Credits")
    void testSystemLedgerDebitCreditInvariantVerification() {
        // Initial state is balanced
        assertTrue(reconciliationService.verifySystemLedgerDebitCreditInvariant(),
                "System-wide ledger must be balanced in valid state");

        // Introduce synthetic unbalance (DEBIT without corresponding CREDIT)
        LedgerEntry unbalancedEntry = ledgerRepository.save(LedgerEntry.builder()
                .transactionId("synthetic_unbalanced_" + UUID.randomUUID())
                .account(payerAccount)
                .amount(new BigDecimal("999.0000"))
                .type(EntryType.DEBIT)
                .build());

        // Invariant check MUST fail
        assertFalse(reconciliationService.verifySystemLedgerDebitCreditInvariant(),
                "System-wide ledger audit must fail when an unbalance exists");

        // Cleanup synthetic entry
        ledgerRepository.delete(unbalancedEntry);

        // Invariant check passes again
        assertTrue(reconciliationService.verifySystemLedgerDebitCreditInvariant(),
                "Ledger must be balanced again after removing corrupted entry");
    }
}
