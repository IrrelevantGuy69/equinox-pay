package com.equinox;

import com.equinox.core.dto.SplitLeg;
import com.equinox.core.entity.*;
import com.equinox.core.repository.*;
import com.equinox.core.service.PaymentEngineService;
import com.equinox.core.service.SettlementService;
import com.equinox.core.service.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MerchantSettlementPhase8IntegrationTest {

    @Autowired
    private VaultService vaultService;

    @Autowired
    private PaymentEngineService paymentEngineService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MerchantFeePlanRepository merchantFeePlanRepository;

    @Autowired
    private SettlementBatchRepository settlementBatchRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Account payerAccount;
    private Account merchantAccount;

    @BeforeEach
    void setup() {
        payerAccount = accountRepository.save(Account.builder()
                .balance(new BigDecimal("1000.0000"))
                .availableBalance(new BigDecimal("1000.0000"))
                .pendingBalance(BigDecimal.ZERO)
                .build());

        merchantAccount = accountRepository.save(Account.builder()
                .balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .pendingBalance(BigDecimal.ZERO)
                .build());
    }

    @Test
    @DisplayName("Phase 8 Invariant: Automated fee deduction with Banker's Rounding and balanced double-entry ledger")
    void testFeeCalculationInvariantWithBankersRounding() {
        // Merchant fee plan: 2.9% + $0.30
        MerchantFeePlan plan = merchantFeePlanRepository.save(MerchantFeePlan.builder()
                .merchantAccount(merchantAccount)
                .percentageRate(new BigDecimal("0.0290"))
                .fixedFee(new BigDecimal("0.3000"))
                .build());

        BigDecimal authAmount = new BigDecimal("100.0000");
        Payment payment = paymentEngineService.authorizePayment(
                payerAccount.getId(),
                merchantAccount.getId(),
                authAmount,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        String captureKey = "cap_" + UUID.randomUUID();
        Payment captured = paymentEngineService.capturePayment(payment.getId(), authAmount, captureKey);

        // Expected fee: 100 * 0.0290 + 0.3000 = 3.2000
        BigDecimal expectedFee = new BigDecimal("3.2000");
        BigDecimal expectedNet = new BigDecimal("96.8000");

        assertEquals(0, expectedFee.compareTo(captured.getFeeAmount()), "Fee must match Banker's Rounding formula");
        assertEquals(0, expectedNet.compareTo(captured.getNetAmount()), "Net amount must equal gross minus fee");
        assertEquals(0, authAmount.compareTo(captured.getNetAmount().add(captured.getFeeAmount())), "Net + Fee must equal gross");

        Account refreshedMerchant = accountRepository.findById(merchantAccount.getId()).orElseThrow();
        assertEquals(0, expectedNet.compareTo(refreshedMerchant.getPendingBalance()), "Captured net must land in pendingBalance");
        assertEquals(0, BigDecimal.ZERO.compareTo(refreshedMerchant.getAvailableBalance()), "Available balance must remain 0 before clearing");

        Account platformFees = accountRepository.findById(VaultService.PLATFORM_FEES_ACCOUNT_ID).orElseThrow();
        assertTrue(platformFees.getBalance().compareTo(expectedFee) >= 0, "Platform fees account must receive the fee");

        // Double-entry validation for capture transaction
        List<LedgerEntry> entries = ledgerRepository.findAll().stream()
                .filter(e -> captureKey.equals(e.getTransactionId()))
                .toList();

        BigDecimal sumDebits = entries.stream()
                .filter(e -> e.getType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal sumCredits = entries.stream()
                .filter(e -> e.getType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, sumDebits.compareTo(sumCredits), "Sum of Debits must equal Sum of Credits for capture leg");
        assertEquals(0, authAmount.compareTo(sumDebits), "Debits must equal captured amount");
    }

    @Test
    @DisplayName("Phase 8 Invariant: Dynamic N-Party Split Payments with Deadlock-Free lock ordering")
    void testNPartySplitPaymentDeadlockFree() throws InterruptedException {
        Account merchantA = accountRepository.save(Account.builder().balance(BigDecimal.ZERO).build());
        Account merchantB = accountRepository.save(Account.builder().balance(BigDecimal.ZERO).build());

        // Merchant A: 2.0% fee (no fixed fee)
        merchantFeePlanRepository.save(MerchantFeePlan.builder()
                .merchantAccount(merchantA)
                .percentageRate(new BigDecimal("0.0200"))
                .fixedFee(BigDecimal.ZERO)
                .build());

        // Merchant B: 3.0% + $0.50 fixed fee
        merchantFeePlanRepository.save(MerchantFeePlan.builder()
                .merchantAccount(merchantB)
                .percentageRate(new BigDecimal("0.0300"))
                .fixedFee(new BigDecimal("0.5000"))
                .build());

        BigDecimal grossA = new BigDecimal("200.0000");
        BigDecimal expectedFeeA = new BigDecimal("4.0000");
        BigDecimal expectedNetA = new BigDecimal("196.0000");

        BigDecimal grossB = new BigDecimal("300.0000");
        BigDecimal expectedFeeB = new BigDecimal("9.5000");
        BigDecimal expectedNetB = new BigDecimal("290.5000");

        BigDecimal totalGross = grossA.add(grossB); // 500.0000
        BigDecimal totalFees = expectedFeeA.add(expectedFeeB); // 13.5000

        String splitKey = "split_" + UUID.randomUUID();
        List<SplitLeg> splits = List.of(
                SplitLeg.builder().merchantAccountId(merchantA.getId()).amount(grossA).build(),
                SplitLeg.builder().merchantAccountId(merchantB.getId()).amount(grossB).build()
        );

        vaultService.processSplitTransfer(splitKey, payerAccount.getId(), splits);

        Account refreshedPayer = accountRepository.findById(payerAccount.getId()).orElseThrow();
        Account refreshedA = accountRepository.findById(merchantA.getId()).orElseThrow();
        Account refreshedB = accountRepository.findById(merchantB.getId()).orElseThrow();

        assertEquals(0, new BigDecimal("500.0000").compareTo(refreshedPayer.getBalance()), "Payer must be debited totalGross");
        assertEquals(0, expectedNetA.compareTo(refreshedA.getPendingBalance()), "Merchant A must receive netPending");
        assertEquals(0, expectedNetB.compareTo(refreshedB.getPendingBalance()), "Merchant B must receive netPending");

        List<LedgerEntry> entries = ledgerRepository.findAll().stream()
                .filter(e -> splitKey.equals(e.getTransactionId()))
                .toList();

        BigDecimal sumDebits = entries.stream()
                .filter(e -> e.getType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal sumCredits = entries.stream()
                .filter(e -> e.getType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, sumDebits.compareTo(sumCredits), "Split transaction debits must equal credits down to exact cent");
        assertEquals(0, totalGross.compareTo(sumDebits), "Total debited must equal total gross");

        // Concurrent test: execute 6 concurrent split payments in opposite leg orders to prove deadlock freedom
        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    String threadKey = "conc_split_" + UUID.randomUUID();
                    // Alternating leg order to verify deterministic UUID lock ordering prevents deadlocks
                    List<SplitLeg> orderedSplits = (idx % 2 == 0)
                            ? List.of(SplitLeg.builder().merchantAccountId(merchantA.getId()).amount(new BigDecimal("10.0000")).build(),
                                      SplitLeg.builder().merchantAccountId(merchantB.getId()).amount(new BigDecimal("10.0000")).build())
                            : List.of(SplitLeg.builder().merchantAccountId(merchantB.getId()).amount(new BigDecimal("10.0000")).build(),
                                      SplitLeg.builder().merchantAccountId(merchantA.getId()).amount(new BigDecimal("10.0000")).build());

                    vaultService.processSplitTransfer(threadKey, payerAccount.getId(), orderedSplits);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(completed, "Concurrent split transfers completed without deadlocks");
    }

    @Test
    @DisplayName("Phase 8 Invariant: T+1 Clearing Sweeper transitions pendingBalance to availableBalance")
    void testTPlusOneClearingTransition() {
        merchantFeePlanRepository.save(MerchantFeePlan.builder()
                .merchantAccount(merchantAccount)
                .percentageRate(new BigDecimal("0.0290"))
                .fixedFee(new BigDecimal("0.3000"))
                .build());

        BigDecimal amount = new BigDecimal("100.0000");
        Payment payment = paymentEngineService.authorizePayment(
                payerAccount.getId(),
                merchantAccount.getId(),
                amount,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        Payment captured = paymentEngineService.capturePayment(payment.getId(), amount, "cap_clear_" + UUID.randomUUID());
        BigDecimal netAmount = captured.getNetAmount();

        Account beforeClearing = accountRepository.findById(merchantAccount.getId()).orElseThrow();
        assertEquals(0, netAmount.compareTo(beforeClearing.getPendingBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(beforeClearing.getAvailableBalance()));
        assertNull(captured.getClearedAt(), "Payment must be uncleared initially");

        // Backdate capturedAt to 25 hours ago to satisfy T+1 cutoff (> 24 hours)
        captured.setCapturedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        paymentRepository.save(captured);

        // Run T+1 sweeper
        List<Payment> cleared = settlementService.clearUnclearedFunds(Instant.now().minus(24, ChronoUnit.HOURS));
        assertTrue(cleared.stream().anyMatch(p -> p.getId().equals(captured.getId())), "Payment must be cleared");

        Account afterClearing = accountRepository.findById(merchantAccount.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(afterClearing.getPendingBalance()), "Pending balance must be 0 after clearing");
        assertEquals(0, netAmount.compareTo(afterClearing.getAvailableBalance()), "Available balance must equal cleared net amount");

        Payment clearedPayment = paymentRepository.findById(captured.getId()).orElseThrow();
        assertNotNull(clearedPayment.getClearedAt(), "clearedAt timestamp must be recorded");

        // Outbox event verification
        boolean eventFound = outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getPayload().contains(captured.getId().toString()) &&
                               e.getPayload().contains("FUNDS_CLEARED"));
        assertTrue(eventFound, "FUNDS_CLEARED outbox event must be emitted");
    }

    @Test
    @DisplayName("Phase 8 Invariant: Proportional fee reversals during refund without orphaned cents")
    void testProportionalRefundReversals() {
        // Merchant fee plan: 3% flat fee
        merchantFeePlanRepository.save(MerchantFeePlan.builder()
                .merchantAccount(merchantAccount)
                .percentageRate(new BigDecimal("0.0300"))
                .fixedFee(BigDecimal.ZERO)
                .build());

        BigDecimal authAmount = new BigDecimal("100.0000");
        Payment payment = paymentEngineService.authorizePayment(
                payerAccount.getId(),
                merchantAccount.getId(),
                authAmount,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        // Capture 100: fee = 3.00, net = 97.00
        Payment captured = paymentEngineService.capturePayment(payment.getId(), authAmount, "cap_ref_" + UUID.randomUUID());
        assertEquals(0, new BigDecimal("3.0000").compareTo(captured.getFeeAmount()));

        // Simulate clearing so merchant has available balance to refund
        captured.setCapturedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        paymentRepository.save(captured);
        settlementService.clearUnclearedFunds(Instant.now().minus(24, ChronoUnit.HOURS));

        Account platformFeesBefore = accountRepository.findById(VaultService.PLATFORM_FEES_ACCOUNT_ID).orElseThrow();
        BigDecimal initialFees = platformFeesBefore.getBalance();

        // Partial Refund of $50 (50% of captured)
        // Fee reversal = 50 * (3.00 / 100) = 1.5000
        // Merchant debit = 50 - 1.50 = 48.5000
        // Payer credit = 50.0000
        String refKey1 = "ref_part_" + UUID.randomUUID();
        Payment refunded1 = paymentEngineService.refundPaymentLifecycle(captured.getId(), new BigDecimal("50.0000"), refKey1);
        assertEquals(PaymentState.PARTIALLY_REFUNDED, refunded1.getState());

        List<LedgerEntry> entries1 = ledgerRepository.findAll().stream()
                .filter(e -> refKey1.equals(e.getTransactionId()))
                .toList();

        BigDecimal debits1 = entries1.stream()
                .filter(e -> e.getType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal credits1 = entries1.stream()
                .filter(e -> e.getType() == EntryType.REFUND || e.getType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, debits1.compareTo(credits1), "Debits must equal credits in partial proportional refund");
        assertEquals(0, new BigDecimal("50.0000").compareTo(credits1));

        // Remaining Refund of $50 -> Full Refund
        String refKey2 = "ref_full_" + UUID.randomUUID();
        Payment refunded2 = paymentEngineService.refundPaymentLifecycle(captured.getId(), new BigDecimal("50.0000"), refKey2);
        assertEquals(PaymentState.REFUNDED, refunded2.getState());

        Account platformFeesAfter = accountRepository.findById(VaultService.PLATFORM_FEES_ACCOUNT_ID).orElseThrow();
        BigDecimal totalFeeReversed = initialFees.subtract(platformFeesAfter.getBalance());
        assertEquals(0, new BigDecimal("3.0000").compareTo(totalFeeReversed), "Total fee reversed must equal original fee exactly");
    }

    @Test
    @DisplayName("Phase 8 Invariant: Daily Payout Batch Execution resets availableBalance and produces itemized CSV")
    void testDailyPayoutBatchExecutionAndCsvStatement() {
        merchantFeePlanRepository.save(MerchantFeePlan.builder()
                .merchantAccount(merchantAccount)
                .percentageRate(new BigDecimal("0.0200"))
                .fixedFee(new BigDecimal("0.5000"))
                .build());

        BigDecimal grossAmount = new BigDecimal("100.0000");
        Payment payment = paymentEngineService.authorizePayment(
                payerAccount.getId(),
                merchantAccount.getId(),
                grossAmount,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        Payment captured = paymentEngineService.capturePayment(payment.getId(), grossAmount, "cap_payout_" + UUID.randomUUID());
        // Fee = 100 * 0.02 + 0.50 = 2.5000, Net = 97.5000
        BigDecimal expectedNet = new BigDecimal("97.5000");

        // Clear funds
        captured.setCapturedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        paymentRepository.save(captured);
        settlementService.clearUnclearedFunds(Instant.now().minus(24, ChronoUnit.HOURS));

        Account payoutTransitBefore = accountRepository.findById(VaultService.PAYOUT_TRANSIT_ACCOUNT_ID).orElseThrow();
        BigDecimal transitBefore = payoutTransitBefore.getBalance();

        // Process Payout Batch
        SettlementBatch batch = settlementService.processMerchantPayout(merchantAccount.getId());
        assertNotNull(batch, "Settlement batch must be created");
        assertEquals(SettlementStatus.PAID, batch.getStatus());
        assertEquals(0, expectedNet.compareTo(batch.getNetPayoutAmount()));
        assertEquals(0, grossAmount.compareTo(batch.getTotalAmount()));

        // Merchant available balance must reset to 0
        Account merchantAfter = accountRepository.findById(merchantAccount.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(merchantAfter.getAvailableBalance()), "Available balance must reset to 0 after payout");

        // Payout transit account must receive the payout amount
        Account payoutTransitAfter = accountRepository.findById(VaultService.PAYOUT_TRANSIT_ACCOUNT_ID).orElseThrow();
        assertEquals(0, transitBefore.add(expectedNet).compareTo(payoutTransitAfter.getBalance()), "Transit account must receive exact funds");

        // CSV Statement verification
        String csv = batch.getCsvStatement();
        assertNotNull(csv);
        assertTrue(csv.startsWith("TransactionID,Date,Type,GrossAmount,FeeAmount,NetAmount"), "CSV must include correct header");
        assertTrue(csv.contains(captured.getId().toString()), "CSV must contain payment ID");
        assertTrue(csv.contains("100.0000,2.5000,97.5000"), "CSV must contain itemized amounts");

        // Outbox event
        boolean payoutEventFound = outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getPayload().contains(batch.getId().toString()) &&
                               e.getPayload().contains("PAYOUT_SETTLED"));
        assertTrue(payoutEventFound, "PAYOUT_SETTLED event must be dispatched to Outbox");
    }
}
