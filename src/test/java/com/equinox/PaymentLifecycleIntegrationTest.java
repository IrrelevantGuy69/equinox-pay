package com.equinox;

import com.equinox.core.entity.Account;
import com.equinox.core.entity.Payment;
import com.equinox.core.entity.PaymentHistory;
import com.equinox.core.entity.PaymentState;
import com.equinox.core.job.PaymentReconciliationJob;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.PaymentHistoryRepository;
import com.equinox.core.repository.PaymentRepository;
import com.equinox.core.service.PaymentEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaymentLifecycleIntegrationTest {

    @Autowired
    private PaymentEngineService paymentEngineService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentReconciliationJob reconciliationJob;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        Account a = accountRepository.save(Account.builder().balance(new BigDecimal("1000.0000")).build());
        Account b = accountRepository.save(Account.builder().balance(new BigDecimal("500.0000")).build());
        accountAId = a.getId();
        accountBId = b.getId();
    }

    @Test
    @DisplayName("Invariant 2: Payment Lifecycle Transitions PENDING -> PROCESSING -> SETTLED & Audit Trail")
    void testSuccessfulPaymentLifecycleAndAuditTrail() {
        Payment payment = paymentEngineService.initializePayment(accountAId, accountBId, new BigDecimal("100.0000"));
        assertNotNull(payment.getId());
        assertEquals(PaymentState.PENDING, payment.getState());
        assertNotNull(payment.getVersion());

        String idempotencyKey = "pay_" + UUID.randomUUID();
        paymentEngineService.processPayment(payment.getId(), idempotencyKey);

        Payment settledPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentState.SETTLED, settledPayment.getState());

        List<PaymentHistory> history = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId());
        assertTrue(history.size() >= 3);
        assertEquals(PaymentState.PENDING, history.get(0).getNewState());
        assertEquals(PaymentState.PROCESSING, history.get(1).getNewState());
        assertEquals(PaymentState.SETTLED, history.get(2).getNewState());
    }

    @Test
    @DisplayName("Invariant 2: Payment Lifecycle Transitions PENDING -> PROCESSING -> FAILED on Insufficient Funds")
    void testFailedPaymentLifecycle() {
        Payment payment = paymentEngineService.initializePayment(accountAId, accountBId, new BigDecimal("999999.0000"));
        String idempotencyKey = "fail_pay_" + UUID.randomUUID();

        assertThrows(RuntimeException.class, () ->
                paymentEngineService.processPayment(payment.getId(), idempotencyKey));

        Payment failedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentState.FAILED, failedPayment.getState());

        List<PaymentHistory> history = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId());
        assertTrue(history.stream().anyMatch(h -> h.getNewState() == PaymentState.FAILED));
    }

    @Test
    @DisplayName("Invariant 2: Sweeper Daemon resolves zombie payments stuck in PROCESSING beyond cutoff")
    void testSweeperDaemonResolvesZombiePayments() {
        Payment payment = paymentEngineService.initializePayment(accountAId, accountBId, new BigDecimal("50.0000"));
        payment.setState(PaymentState.PROCESSING);
        payment.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        paymentRepository.saveAndFlush(payment);

        reconciliationJob.sweepZombiePayments();

        Payment sweptPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentState.FAILED, sweptPayment.getState());
    }
}
