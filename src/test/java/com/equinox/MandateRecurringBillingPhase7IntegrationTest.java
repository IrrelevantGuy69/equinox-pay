package com.equinox;

import com.equinox.core.entity.*;
import com.equinox.core.job.RecurringBillingJob;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.MandateRepository;
import com.equinox.core.repository.OutboxEventRepository;
import com.equinox.core.service.MandateService;
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
class MandateRecurringBillingPhase7IntegrationTest {

    @Autowired
    private MandateService mandateService;

    @Autowired
    private MandateRepository mandateRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RecurringBillingJob recurringBillingJob;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Account payerAccount;
    private Account merchantAccount;

    @BeforeEach
    void setup() {
        payerAccount = accountRepository.save(Account.builder().balance(new BigDecimal("1000.0000")).build());
        merchantAccount = accountRepository.save(Account.builder().balance(new BigDecimal("100.0000")).build());
    }

    @Test
    @DisplayName("Phase 7 Invariant: Deterministic Cycle Idempotency prevents double debiting")
    void testMandateDeterministicCycleIdempotency() {
        BigDecimal cap = new BigDecimal("50.0000");
        Mandate mandate = mandateService.createMandate(
                payerAccount,
                merchantAccount,
                cap,
                MandateFrequency.MONTHLY,
                Instant.now().plus(365, ChronoUnit.DAYS),
                Instant.now().minusSeconds(10) // Due now
        );

        // Execute Charge #1
        mandateService.executeMandateCharge(mandate.getId(), cap);

        Account payerAfter1 = accountRepository.findById(payerAccount.getId()).orElseThrow();
        Account merchantAfter1 = accountRepository.findById(merchantAccount.getId()).orElseThrow();
        assertEquals(new BigDecimal("950.0000"), payerAfter1.getBalance());
        assertEquals(new BigDecimal("150.0000"), merchantAfter1.getBalance());

        // Re-execute charge with the same cycle billing date directly to simulate runner retry
        mandateService.executeMandateCharge(mandate.getId(), cap);

        // Balance must remain unchanged: exactly one debit occurred
        Account payerAfter2 = accountRepository.findById(payerAccount.getId()).orElseThrow();
        Account merchantAfter2 = accountRepository.findById(merchantAccount.getId()).orElseThrow();
        assertEquals(new BigDecimal("950.0000"), payerAfter2.getBalance());
        assertEquals(new BigDecimal("150.0000"), merchantAfter2.getBalance());
    }

    @Test
    @DisplayName("Phase 7 Invariant: Max Debit Cap enforcement rejects excessive charges")
    void testMaxDebitCapEnforcement() {
        BigDecimal cap = new BigDecimal("25.0000");
        Mandate mandate = mandateService.createMandate(
                payerAccount,
                merchantAccount,
                cap,
                MandateFrequency.WEEKLY,
                Instant.now().plus(30, ChronoUnit.DAYS),
                Instant.now()
        );

        assertThrows(IllegalArgumentException.class, () ->
                mandateService.executeMandateCharge(mandate.getId(), new BigDecimal("30.0000")));
    }

    @Test
    @DisplayName("Phase 7 Invariant: Dunning Policy increments retryCount and transitions to PAST_DUE")
    void testDunningPolicyFailureAndPastDue() {
        // Payer only has 5.00, but cap and charge is 50.00 -> Insufficient funds
        Account poorPayer = accountRepository.save(Account.builder().balance(new BigDecimal("5.0000")).build());

        Mandate mandate = mandateService.createMandate(
                poorPayer,
                merchantAccount,
                new BigDecimal("50.0000"),
                MandateFrequency.DAILY,
                Instant.now().plus(30, ChronoUnit.DAYS),
                Instant.now().minusSeconds(10)
        );

        // Attempt 1 fails -> retryCount = 1
        mandateService.executeMandateCharge(mandate.getId(), new BigDecimal("50.0000"));
        Mandate m1 = mandateRepository.findById(mandate.getId()).orElseThrow();
        assertEquals(1, m1.getRetryCount());
        assertEquals(MandateStatus.ACTIVE, m1.getStatus());

        // Attempt 2 fails -> retryCount = 2
        mandateService.executeMandateCharge(mandate.getId(), new BigDecimal("50.0000"));
        Mandate m2 = mandateRepository.findById(mandate.getId()).orElseThrow();
        assertEquals(2, m2.getRetryCount());
        assertEquals(MandateStatus.ACTIVE, m2.getStatus());

        // Attempt 3 fails -> transitions to PAST_DUE
        mandateService.executeMandateCharge(mandate.getId(), new BigDecimal("50.0000"));
        Mandate m3 = mandateRepository.findById(mandate.getId()).orElseThrow();
        assertEquals(3, m3.getRetryCount());
        assertEquals(MandateStatus.PAST_DUE, m3.getStatus());
    }

    @Test
    @DisplayName("Phase 7 Invariant: Pre-Debit Notification job publishes Outbox event for upcoming charges")
    void testPreDebitNotificationDispatch() {
        // Create mandate due in 30 hours (inside 24-48h window)
        Instant upcomingDate = Instant.now().plus(30, ChronoUnit.HOURS);
        Mandate mandate = mandateService.createMandate(
                payerAccount,
                merchantAccount,
                new BigDecimal("40.0000"),
                MandateFrequency.MONTHLY,
                Instant.now().plus(365, ChronoUnit.DAYS),
                upcomingDate
        );

        mandateService.dispatchPreDebitNotifications();

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        boolean dispatched = outboxEvents.stream()
                .anyMatch(e -> e.getPayload().contains(mandate.getId().toString()) &&
                               e.getPayload().contains("MANDATE_PRE_DEBIT_NOTIFICATION"));

        assertTrue(dispatched, "Pre-debit notification Outbox event must be dispatched for upcoming mandate");
    }
}
