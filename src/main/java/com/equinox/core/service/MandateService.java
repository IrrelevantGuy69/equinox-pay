package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.*;
import com.equinox.core.repository.MandateRepository;
import com.equinox.core.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MandateService {

    private final MandateRepository mandateRepository;
    private final VaultService vaultService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRY_COUNT = 3;

    @Transactional
    public Mandate createMandate(Account payerAccount, Account merchantAccount, BigDecimal maxDebitCap,
                                 MandateFrequency frequency, Instant validUntil, Instant initialBillingDate) {
        if (maxDebitCap == null || maxDebitCap.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Mandate max debit cap must be positive.");
        }
        if (payerAccount.getId().equals(merchantAccount.getId())) {
            throw new IllegalArgumentException("Payer and Merchant accounts cannot be the same.");
        }

        Mandate mandate = Mandate.builder()
                .payerAccount(payerAccount)
                .merchantAccount(merchantAccount)
                .maxDebitCap(maxDebitCap)
                .frequency(frequency)
                .status(MandateStatus.ACTIVE)
                .validUntil(validUntil)
                .nextBillingDate(initialBillingDate != null ? initialBillingDate : Instant.now())
                .retryCount(0)
                .build();

        mandate = mandateRepository.save(mandate);
        publishMandateOutboxEvent(mandate, "MANDATE_CREATED", BigDecimal.ZERO, "Mandate registered successfully");
        return mandate;
    }

    /**
     * Compute deterministic cycle idempotency key.
     * E.g. "MANDATE_{uuid}_{yyyy_MM}" for MONTHLY, "{yyyy_MM_dd}" for DAILY, "{yyyy_w}" for WEEKLY.
     */
    public String generateCycleIdempotencyKey(Mandate mandate, Instant billingDate) {
        ZonedDateTime utcTime = (billingDate != null ? billingDate : Instant.now()).atZone(ZoneOffset.UTC);
        String cycleId;
        switch (mandate.getFrequency()) {
            case DAILY -> cycleId = String.format("%d_%02d_%02d", utcTime.getYear(), utcTime.getMonthValue(), utcTime.getDayOfMonth());
            case WEEKLY -> {
                int week = utcTime.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                cycleId = String.format("%d_W%02d", utcTime.getYear(), week);
            }
            case MONTHLY -> cycleId = String.format("%d_%02d", utcTime.getYear(), utcTime.getMonthValue());
            default -> cycleId = String.format("%d_%02d", utcTime.getYear(), utcTime.getMonthValue());
        }
        return "MANDATE_" + mandate.getId() + "_" + cycleId;
    }

    /**
     * Executes a single mandate charge deterministically.
     */
    public void executeMandateCharge(UUID mandateId, BigDecimal chargeAmount) {
        Mandate mandate = mandateRepository.findById(mandateId)
                .orElseThrow(() -> new IllegalArgumentException("Mandate not found: " + mandateId));

        if (mandate.getStatus() != MandateStatus.ACTIVE) {
            log.warn("Skipping mandate {}: Not in ACTIVE status ({})", mandate.getId(), mandate.getStatus());
            return;
        }

        if (mandate.getValidUntil() != null && Instant.now().isAfter(mandate.getValidUntil())) {
            mandate.setStatus(MandateStatus.EXPIRED);
            mandateRepository.save(mandate);
            return;
        }

        if (chargeAmount.compareTo(mandate.getMaxDebitCap()) > 0) {
            throw new IllegalArgumentException("Charge amount " + chargeAmount + " exceeds max debit cap " + mandate.getMaxDebitCap());
        }

        // Use the actual current nextBillingDate (or lastBilledAt cycle if already billed)
        Instant cycleDate = (mandate.getLastBilledAt() != null && mandate.getRetryCount() == 0)
                ? mandate.getLastBilledAt()
                : mandate.getNextBillingDate();

        String idempotencyKey = generateCycleIdempotencyKey(mandate, cycleDate);

        try {
            processMandateTransferAtomic(mandateId, idempotencyKey, chargeAmount);
        } catch (Exception e) {
            log.error("Mandate charge failed for mandate {}: {}", mandate.getId(), e.getMessage());
            handleDunningFailureAtomic(mandateId, chargeAmount, e.getMessage());
        }
    }

    public void executeDueMandates() {
        Instant now = Instant.now();
        List<Mandate> dueMandates = mandateRepository.findByStatusAndNextBillingDateBefore(MandateStatus.ACTIVE, now);

        if (!dueMandates.isEmpty()) {
            log.info("Executing recurring billing for {} due mandates", dueMandates.size());
        }

        for (Mandate mandate : dueMandates) {
            try {
                executeMandateCharge(mandate.getId(), mandate.getMaxDebitCap());
            } catch (Exception e) {
                log.error("Error processing mandate {} in billing cycle: {}", mandate.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void dispatchPreDebitNotifications() {
        Instant now = Instant.now();
        Instant windowStart = now.plus(24, ChronoUnit.HOURS);
        Instant windowEnd = now.plus(48, ChronoUnit.HOURS);

        List<Mandate> upcoming = mandateRepository.findByStatusAndNextBillingDateBetween(
                MandateStatus.ACTIVE, windowStart, windowEnd
        );

        for (Mandate mandate : upcoming) {
            try {
                publishMandateOutboxEvent(
                        mandate,
                        "MANDATE_PRE_DEBIT_NOTIFICATION",
                        mandate.getMaxDebitCap(),
                        "Debit scheduled on: " + mandate.getNextBillingDate()
                );
            } catch (Exception e) {
                log.error("Failed to dispatch pre-debit notification for mandate {}: {}", mandate.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void processMandateTransferAtomic(UUID mandateId, String idempotencyKey, BigDecimal chargeAmount) {
        Mandate mandate = mandateRepository.findById(mandateId)
                .orElseThrow(() -> new IllegalArgumentException("Mandate not found: " + mandateId));

        vaultService.processTransfer(
                idempotencyKey,
                mandate.getPayerAccount().getId(),
                mandate.getMerchantAccount().getId(),
                chargeAmount
        );

        // Success: Reset retry count, advance next billing date, record last billed
        mandate.setLastBilledAt(Instant.now());
        mandate.setRetryCount(0);
        mandate.setNextBillingDate(calculateNextBillingDate(mandate.getNextBillingDate(), mandate.getFrequency()));
        mandateRepository.save(mandate);

        publishMandateOutboxEvent(mandate, "MANDATE_CHARGED", chargeAmount, "Billed cycle: " + idempotencyKey);
        log.info("Mandate {} successfully charged {} for cycle {}", mandate.getId(), chargeAmount, idempotencyKey);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void handleDunningFailureAtomic(UUID mandateId, BigDecimal chargeAmount, String failureReason) {
        Mandate mandate = mandateRepository.findById(mandateId).orElse(null);
        if (mandate == null) return;

        int newRetryCount = mandate.getRetryCount() + 1;
        mandate.setRetryCount(newRetryCount);

        if (newRetryCount >= MAX_RETRY_COUNT) {
            mandate.setStatus(MandateStatus.PAST_DUE);
            mandateRepository.save(mandate);
            publishMandateOutboxEvent(mandate, "MANDATE_PAST_DUE", chargeAmount, "Exceeded retry threshold (" + MAX_RETRY_COUNT + "). Marked PAST_DUE. " + failureReason);
            log.warn("Mandate {} transitioned to PAST_DUE after {} failed attempts", mandate.getId(), newRetryCount);
        } else {
            // Dunning retry intervals: attempt 1 -> +1 day, attempt 2 -> +3 days
            long delayDays = (newRetryCount == 1) ? 1 : 3;
            mandate.setNextBillingDate(Instant.now().plus(delayDays, ChronoUnit.DAYS));
            mandateRepository.save(mandate);
            publishMandateOutboxEvent(mandate, "MANDATE_FAILED", chargeAmount, "Attempt " + newRetryCount + " failed: " + failureReason);
            log.info("Mandate {} retry scheduled in {} days", mandate.getId(), delayDays);
        }
    }

    public Instant calculateNextBillingDate(Instant fromDate, MandateFrequency frequency) {
        Instant base = (fromDate != null) ? fromDate : Instant.now();
        ZonedDateTime zdt = base.atZone(ZoneOffset.UTC);
        return switch (frequency) {
            case DAILY -> zdt.plusDays(1).toInstant();
            case WEEKLY -> zdt.plusWeeks(1).toInstant();
            case MONTHLY -> zdt.plusMonths(1).toInstant();
        };
    }

    public void publishMandateOutboxEvent(Mandate mandate, String eventType, BigDecimal amount, String description) {
        try {
            PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                    .paymentId(mandate.getId())
                    .fromAccountId(mandate.getPayerAccount().getId())
                    .toAccountId(mandate.getMerchantAccount().getId())
                    .amount(amount != null ? amount : mandate.getMaxDebitCap())
                    .status(eventType)
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventType("payment-notifications")
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize mandate outbox event", ex);
        }
    }
}
