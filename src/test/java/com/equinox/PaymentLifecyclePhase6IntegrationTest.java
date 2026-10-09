package com.equinox;

import com.equinox.core.entity.Account;
import com.equinox.core.entity.EntryType;
import com.equinox.core.entity.LedgerEntry;
import com.equinox.core.entity.Payment;
import com.equinox.core.entity.PaymentState;
import com.equinox.core.job.AutoVoidExpiredAuthorizationsJob;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.LedgerRepository;
import com.equinox.core.repository.PaymentRepository;
import com.equinox.core.service.PaymentEngineService;
import com.equinox.core.service.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaymentLifecyclePhase6IntegrationTest {

    @Autowired
    private VaultService vaultService;

    @Autowired
    private PaymentEngineService paymentEngineService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private AutoVoidExpiredAuthorizationsJob autoVoidJob;

    private UUID payerAccountId;
    private UUID merchantAccountId;

    @BeforeEach
    void setup() {
        // Ensure HOLD_SUSPENSE_ACCOUNT is present
        accountRepository.findById(VaultService.HOLD_SUSPENSE_ACCOUNT_ID).orElseGet(() ->
                accountRepository.save(Account.builder()
                        .id(VaultService.HOLD_SUSPENSE_ACCOUNT_ID)
                        .balance(BigDecimal.ZERO)
                        .build()));

        Account payer = accountRepository.save(Account.builder().balance(new BigDecimal("1000.0000")).build());
        Account merchant = accountRepository.save(Account.builder().balance(new BigDecimal("500.0000")).build());
        payerAccountId = payer.getId();
        merchantAccountId = merchant.getId();
    }

    private void assertDoubleEntryZeroSum(String txnId) {
        List<LedgerEntry> entries = ledgerRepository.findAll().stream()
                .filter(e -> txnId.equals(e.getTransactionId()))
                .toList();

        assertFalse(entries.isEmpty(), "Ledger entries must be recorded for txn: " + txnId);

        BigDecimal debits = entries.stream()
                .filter(e -> e.getType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal credits = entries.stream()
                .filter(e -> e.getType() == EntryType.CREDIT || e.getType() == EntryType.REFUND)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, debits.compareTo(credits),
                "Total Debits (" + debits + ") must strictly equal Total Credits (" + credits + ") for txn " + txnId);
    }

    @Test
    @DisplayName("Phase 6 Invariant: Full Authorize and Capture double-entry balance equality")
    void testAuthorizeAndFullCapture() {
        BigDecimal authAmount = new BigDecimal("200.0000");
        Payment payment = paymentEngineService.authorizePayment(payerAccountId, merchantAccountId, authAmount, null);

        assertEquals(PaymentState.AUTHORIZED, payment.getState());
        Account payer = accountRepository.findById(payerAccountId).orElseThrow();
        Account suspense = accountRepository.findById(VaultService.HOLD_SUSPENSE_ACCOUNT_ID).orElseThrow();

        assertEquals(new BigDecimal("800.0000"), payer.getBalance());
        assertTrue(suspense.getBalance().compareTo(authAmount) >= 0);

        String captureKey = "cap_" + UUID.randomUUID();
        Payment captured = paymentEngineService.capturePayment(payment.getId(), authAmount, captureKey);

        assertEquals(PaymentState.CAPTURED, captured.getState());
        assertEquals(authAmount, captured.getCapturedAmount());

        Account merchant = accountRepository.findById(merchantAccountId).orElseThrow();
        assertEquals(new BigDecimal("700.0000"), merchant.getBalance());

        assertDoubleEntryZeroSum(captureKey);
    }

    @Test
    @DisplayName("Phase 6 Invariant: Partial Capture releases unreserved funds to Payer")
    void testPartialCaptureReleasesUnreservedHold() {
        BigDecimal authAmount = new BigDecimal("300.0000");
        BigDecimal captureAmount = new BigDecimal("120.0000");
        BigDecimal expectedRelease = authAmount.subtract(captureAmount); // 180.0000

        Payment payment = paymentEngineService.authorizePayment(payerAccountId, merchantAccountId, authAmount, null);

        String captureKey = "part_cap_" + UUID.randomUUID();
        Payment captured = paymentEngineService.capturePayment(payment.getId(), captureAmount, captureKey);

        assertEquals(PaymentState.CAPTURED, captured.getState());
        assertEquals(captureAmount, captured.getCapturedAmount());

        Account payer = accountRepository.findById(payerAccountId).orElseThrow();
        Account merchant = accountRepository.findById(merchantAccountId).orElseThrow();

        // Initial 1000 - 300 hold + 180 released = 880
        assertEquals(new BigDecimal("880.0000"), payer.getBalance());
        // Initial 500 + 120 captured = 620
        assertEquals(new BigDecimal("620.0000"), merchant.getBalance());

        assertDoubleEntryZeroSum(captureKey);
    }

    @Test
    @DisplayName("Phase 6 Invariant: Void returns held funds to Payer")
    void testVoidAuthorization() {
        BigDecimal authAmount = new BigDecimal("150.0000");
        Payment payment = paymentEngineService.authorizePayment(payerAccountId, merchantAccountId, authAmount, null);

        String voidKey = "void_" + UUID.randomUUID();
        Payment voided = paymentEngineService.voidPayment(payment.getId(), voidKey);

        assertEquals(PaymentState.VOIDED, voided.getState());

        Account payer = accountRepository.findById(payerAccountId).orElseThrow();
        assertEquals(new BigDecimal("1000.0000"), payer.getBalance());

        assertDoubleEntryZeroSum(voidKey);
    }

    @Test
    @DisplayName("Phase 6 Invariant: Full and Partial Refund with EntryType.REFUND and idempotency key")
    void testFullAndPartialRefundLifecycle() {
        BigDecimal authAmount = new BigDecimal("200.0000");
        Payment payment = paymentEngineService.authorizePayment(payerAccountId, merchantAccountId, authAmount, null);

        String captureKey = "cap_" + UUID.randomUUID();
        payment = paymentEngineService.capturePayment(payment.getId(), authAmount, captureKey);

        // Partial Refund 1: 50.00
        String refKey1 = "ref_" + UUID.randomUUID();
        payment = paymentEngineService.refundPaymentLifecycle(payment.getId(), new BigDecimal("50.0000"), refKey1);
        assertEquals(PaymentState.PARTIALLY_REFUNDED, payment.getState());
        assertEquals(new BigDecimal("50.0000"), payment.getRefundedAmount());
        assertDoubleEntryZeroSum(refKey1);

        // Partial Refund 2: 150.00 -> Complete Refund
        String refKey2 = "ref_" + UUID.randomUUID();
        payment = paymentEngineService.refundPaymentLifecycle(payment.getId(), new BigDecimal("150.0000"), refKey2);
        assertEquals(PaymentState.REFUNDED, payment.getState());
        assertEquals(new BigDecimal("200.0000"), payment.getRefundedAmount());
        assertDoubleEntryZeroSum(refKey2);

        final UUID targetPaymentId = payment.getId();
        // Attempting to refund beyond captured amount must fail
        assertThrows(RuntimeException.class, () ->
                paymentEngineService.refundPaymentLifecycle(targetPaymentId, new BigDecimal("10.0000"), "ref_excess"));
    }

    @Test
    @DisplayName("Phase 6 Concurrency: Parallel refunds cannot exceed captured amount")
    void testParallelRefundConcurrency() throws InterruptedException {
        BigDecimal authAmount = new BigDecimal("100.0000");
        Payment payment = paymentEngineService.authorizePayment(payerAccountId, merchantAccountId, authAmount, null);
        payment = paymentEngineService.capturePayment(payment.getId(), authAmount, "cap_" + UUID.randomUUID());

        final UUID paymentId = payment.getId();
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    // Each thread attempts to refund 60.00. Since total is 100.00, only 1 can succeed!
                    paymentEngineService.refundPaymentLifecycle(paymentId, new BigDecimal("60.0000"), "par_ref_" + index + "_" + UUID.randomUUID());
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Only one refund of 60.00 should succeed out of 100.00 captured amount");
    }

    @Test
    @DisplayName("Phase 6 Invariant: AutoVoidExpiredAuthorizationsJob voids expired payments")
    void testAutoVoidSweeperJob() {
        Payment expiredPayment = Payment.builder()
                .fromAccountId(payerAccountId)
                .toAccountId(merchantAccountId)
                .amount(new BigDecimal("50.0000"))
                .authorizedAmount(new BigDecimal("50.0000"))
                .capturedAmount(BigDecimal.ZERO)
                .refundedAmount(BigDecimal.ZERO)
                .authorizationExpiresAt(Instant.now().minusSeconds(100))
                .state(PaymentState.AUTHORIZED)
                .build();

        vaultService.processAuthorize("seed_auth_" + UUID.randomUUID(), payerAccountId, new BigDecimal("50.0000"));
        expiredPayment = paymentRepository.save(expiredPayment);

        autoVoidJob.voidExpiredAuthorizations();

        Payment voided = paymentRepository.findById(expiredPayment.getId()).orElseThrow();
        assertEquals(PaymentState.VOIDED, voided.getState());
    }
}
