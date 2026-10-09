package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.*;
import com.equinox.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Phase 8.1: T+1 Clearing Sweeper
     * Clears uncleared funds by moving them from merchant pendingBalance to availableBalance.
     */
    @Transactional
    public List<Payment> clearUnclearedFunds(Instant cutoffTime) {
        List<Payment> uncleared = paymentRepository.findByStateAndClearedAtIsNullAndCapturedAtLessThanEqual(
                PaymentState.CAPTURED, cutoffTime
        );

        if (!uncleared.isEmpty()) {
            log.info("Found {} uncleared payments eligible for T+1 clearing at cutoff {}", uncleared.size(), cutoffTime);
        }

        List<Payment> clearedList = new ArrayList<>();
        for (Payment payment : uncleared) {
            try {
                UUID merchantId = payment.getToAccountId();
                Account merchant = accountRepository.findByIdForUpdate(merchantId)
                        .orElseThrow(() -> new IllegalArgumentException("Merchant account not found: " + merchantId));

                BigDecimal netAmount = payment.getNetAmount() != null ? payment.getNetAmount() : payment.getCapturedAmount();
                if (netAmount == null) {
                    netAmount = payment.getAmount();
                }

                merchant.debitPending(netAmount);
                merchant.creditAvailable(netAmount);
                accountRepository.save(merchant);

                payment.setClearedAt(Instant.now());
                paymentRepository.save(payment);
                clearedList.add(payment);

                publishSettlementOutboxEvent(
                        payment.getId(),
                        payment.getFromAccountId(),
                        merchantId,
                        netAmount,
                        "FUNDS_CLEARED",
                        "Net funds cleared to available balance"
                );
                log.info("Cleared funds of {} for payment {} to merchant {}", netAmount, payment.getId(), merchantId);
            } catch (Exception ex) {
                log.error("Failed to clear funds for payment {}: {}", payment.getId(), ex.getMessage(), ex);
            }
        }
        return clearedList;
    }

    /**
     * Phase 8.2: Payout Batch Execution for a Merchant
     */
    @Transactional
    public SettlementBatch processMerchantPayout(UUID merchantAccountId) {
        if (merchantAccountId.equals(VaultService.HOLD_SUSPENSE_ACCOUNT_ID) ||
            merchantAccountId.equals(VaultService.PLATFORM_FEES_ACCOUNT_ID) ||
            merchantAccountId.equals(VaultService.PAYOUT_TRANSIT_ACCOUNT_ID)) {
            throw new IllegalArgumentException("System accounts cannot be paid out.");
        }

        UUID firstLock = merchantAccountId.compareTo(VaultService.PAYOUT_TRANSIT_ACCOUNT_ID) < 0
                ? merchantAccountId
                : VaultService.PAYOUT_TRANSIT_ACCOUNT_ID;
        UUID secondLock = merchantAccountId.compareTo(VaultService.PAYOUT_TRANSIT_ACCOUNT_ID) < 0
                ? VaultService.PAYOUT_TRANSIT_ACCOUNT_ID
                : merchantAccountId;

        Account firstAccount = accountRepository.findByIdForUpdate(firstLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLock));
        Account secondAccount = accountRepository.findByIdForUpdate(secondLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLock));

        Account merchant = merchantAccountId.equals(firstLock) ? firstAccount : secondAccount;
        Account payoutTransit = VaultService.PAYOUT_TRANSIT_ACCOUNT_ID.equals(firstLock) ? firstAccount : secondAccount;

        BigDecimal payoutAmount = merchant.getAvailableBalance();
        if (payoutAmount == null || payoutAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Merchant {} has no available balance for payout.", merchantAccountId);
            return null;
        }

        // Fetch cleared payments for this merchant not yet linked to any batch
        List<Payment> candidatePayments = paymentRepository.findByToAccountIdAndStateAndClearedAtIsNotNull(
                merchantAccountId, PaymentState.CAPTURED
        );
        List<Payment> batchPayments = candidatePayments.stream()
                .filter(p -> !settlementItemRepository.existsByPaymentId(p.getId()))
                .toList();

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;

        for (Payment p : batchPayments) {
            totalGross = totalGross.add(p.getCapturedAmount() != null ? p.getCapturedAmount() : p.getAmount());
            totalFees = totalFees.add(p.getFeeAmount() != null ? p.getFeeAmount() : BigDecimal.ZERO);
        }

        if (batchPayments.isEmpty()) {
            totalGross = payoutAmount;
            totalFees = BigDecimal.ZERO;
        }

        String batchReference = "BATCH-" + UUID.randomUUID();

        // Transfer funds from merchant availableBalance to Payout Transit Account
        merchant.debitAvailable(payoutAmount);
        payoutTransit.creditAvailable(payoutAmount);

        String idempotencyKey = "PAYOUT_" + batchReference;
        LedgerEntry debitMerchant = LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(merchant)
                .amount(payoutAmount)
                .type(EntryType.DEBIT)
                .build();

        LedgerEntry creditTransit = LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(payoutTransit)
                .amount(payoutAmount)
                .type(EntryType.CREDIT)
                .build();

        ledgerRepository.saveAll(List.of(debitMerchant, creditTransit));
        accountRepository.saveAll(List.of(merchant, payoutTransit));

        // Generate Itemized CSV Statement
        StringWriter csvWriter = new StringWriter();
        csvWriter.write("TransactionID,Date,Type,GrossAmount,FeeAmount,NetAmount\n");
        for (Payment p : batchPayments) {
            BigDecimal gross = p.getCapturedAmount() != null ? p.getCapturedAmount() : p.getAmount();
            BigDecimal fee = p.getFeeAmount() != null ? p.getFeeAmount() : BigDecimal.ZERO;
            BigDecimal net = p.getNetAmount() != null ? p.getNetAmount() : gross.subtract(fee);
            csvWriter.write(String.format("%s,%s,PAYMENT,%s,%s,%s\n",
                    p.getId(),
                    p.getCapturedAt() != null ? p.getCapturedAt().toString() : Instant.now().toString(),
                    gross.toPlainString(),
                    fee.toPlainString(),
                    net.toPlainString()));
        }

        SettlementBatch batch = SettlementBatch.builder()
                .batchReference(batchReference)
                .merchantAccount(merchant)
                .totalAmount(totalGross)
                .totalFees(totalFees)
                .netPayoutAmount(payoutAmount)
                .status(SettlementStatus.PAID)
                .clearedAt(Instant.now())
                .csvStatement(csvWriter.toString())
                .build();

        batch = settlementBatchRepository.save(batch);

        List<SettlementItem> items = new ArrayList<>();
        for (Payment p : batchPayments) {
            BigDecimal gross = p.getCapturedAmount() != null ? p.getCapturedAmount() : p.getAmount();
            BigDecimal fee = p.getFeeAmount() != null ? p.getFeeAmount() : BigDecimal.ZERO;
            BigDecimal net = p.getNetAmount() != null ? p.getNetAmount() : gross.subtract(fee);
            items.add(SettlementItem.builder()
                    .settlementBatch(batch)
                    .payment(p)
                    .grossAmount(gross)
                    .feeAmount(fee)
                    .netAmount(net)
                    .build());
        }
        if (!items.isEmpty()) {
            settlementItemRepository.saveAll(items);
        }

        publishSettlementOutboxEvent(
                batch.getId(),
                merchantAccountId,
                VaultService.PAYOUT_TRANSIT_ACCOUNT_ID,
                payoutAmount,
                "PAYOUT_SETTLED",
                "batchRef=" + batchReference + "; downloadUrl=/api/v1/settlements/" + batch.getId() + "/statement.csv"
        );

        log.info("Successfully settled payout batch {} of {} for merchant {}", batchReference, payoutAmount, merchantAccountId);
        return batch;
    }

    /**
     * Phase 8.2: Run daily payouts for all eligible merchants
     */
    @Transactional
    public List<SettlementBatch> processDailyPayoutBatches() {
        List<Account> eligibleMerchants = accountRepository.findByAvailableBalanceGreaterThan(BigDecimal.ZERO);
        List<SettlementBatch> processed = new ArrayList<>();

        for (Account account : eligibleMerchants) {
            UUID accId = account.getId();
            if (accId.equals(VaultService.HOLD_SUSPENSE_ACCOUNT_ID) ||
                accId.equals(VaultService.PLATFORM_FEES_ACCOUNT_ID) ||
                accId.equals(VaultService.PAYOUT_TRANSIT_ACCOUNT_ID)) {
                continue;
            }
            try {
                SettlementBatch batch = processMerchantPayout(accId);
                if (batch != null) {
                    processed.add(batch);
                }
            } catch (Exception e) {
                log.error("Failed to process payout for merchant {}: {}", accId, e.getMessage(), e);
            }
        }
        return processed;
    }

    private void publishSettlementOutboxEvent(UUID referenceId, UUID fromId, UUID toId, BigDecimal amount, String eventType, String metadata) {
        try {
            PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                    .paymentId(referenceId)
                    .fromAccountId(fromId)
                    .toAccountId(toId)
                    .amount(amount)
                    .status(eventType)
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventType("payment-notifications")
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize settlement outbox event", ex);
        }
    }
}
