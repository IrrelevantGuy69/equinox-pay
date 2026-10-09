package com.equinox.core.service;

import com.equinox.core.dto.BankSettlementRecord;
import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.*;
import com.equinox.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final BankFileParserService bankFileParserService;
    private final PaymentRepository paymentRepository;
    private final LedgerRepository ledgerRepository;
    private final ReconciliationBatchRepository reconciliationBatchRepository;
    private final ReconciliationDisputeRepository reconciliationDisputeRepository;
    private final PaymentEngineService paymentEngineService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Phase 9: Reconciles an external central bank settlement CSV file against internal records.
     */
    @Transactional
    public ReconciliationBatch reconcileBankFile(String sourceFileName, Reader csvReader, Instant windowStart, Instant windowEnd) {
        String batchRef = "RECON_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ReconciliationBatch batch = ReconciliationBatch.builder()
                .batchReference(batchRef)
                .sourceFileName(sourceFileName)
                .status(ReconciliationBatchStatus.IN_PROGRESS)
                .startedAt(Instant.now())
                .build();
        batch = reconciliationBatchRepository.save(batch);

        List<ReconciliationDispute> disputes = new ArrayList<>();
        Set<UUID> matchedOrDisputedPaymentIds = new HashSet<>();
        int[] totalRecords = {0};
        int[] matchedCount = {0};
        int[] discrepancyCount = {0};

        final ReconciliationBatch currentBatch = batch;

        // 1. Stream parse records
        bankFileParserService.parseStreaming(csvReader, record -> {
            totalRecords[0]++;
            UUID paymentId = null;
            try {
                paymentId = UUID.fromString(record.getTransactionId());
            } catch (Exception ignored) {
            }

            Optional<Payment> paymentOpt = (paymentId != null)
                    ? paymentRepository.findById(paymentId)
                    : Optional.empty();

            if (paymentOpt.isEmpty()) {
                // ORPHANED_RECORD: Bank reported a transaction that does not exist in Equinox Pay
                discrepancyCount[0]++;
                ReconciliationDispute dispute = ReconciliationDispute.builder()
                        .reconciliationBatch(currentBatch)
                        .payment(null)
                        .bankReference(record.getBankReference())
                        .discrepancyType(DiscrepancyType.ORPHANED_RECORD)
                        .expectedAmount(BigDecimal.ZERO)
                        .actualBankAmount(record.getAmount())
                        .discrepancyDifference(record.getAmount())
                        .status(DisputeStatus.OPEN)
                        .resolutionNotes("Orphaned record: Transaction " + record.getTransactionId() + " not found in internal ledger.")
                        .build();
                disputes.add(dispute);
                publishReconciliationOutboxEvent(null, record.getAmount(), "RECONCILIATION_ANOMALY_DETECTED",
                        "Orphaned bank record: " + record.getTransactionId());
                return;
            }

            Payment payment = paymentOpt.get();
            matchedOrDisputedPaymentIds.add(payment.getId());

            BigDecimal expectedAmount = payment.getCapturedAmount() != null
                    ? payment.getCapturedAmount()
                    : payment.getAmount();

            BigDecimal bankAmountRounded = record.getAmount().setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal expectedAmountRounded = expectedAmount.setScale(2, RoundingMode.HALF_EVEN);

            if (expectedAmountRounded.compareTo(bankAmountRounded) == 0) {
                // 100% MATCHED
                matchedCount[0]++;
            } else {
                // AMOUNT_MISMATCH
                discrepancyCount[0]++;
                BigDecimal diff = record.getAmount().subtract(expectedAmount);
                ReconciliationDispute dispute = ReconciliationDispute.builder()
                        .reconciliationBatch(currentBatch)
                        .payment(payment)
                        .bankReference(record.getBankReference())
                        .discrepancyType(DiscrepancyType.AMOUNT_MISMATCH)
                        .expectedAmount(expectedAmount)
                        .actualBankAmount(record.getAmount())
                        .discrepancyDifference(diff)
                        .status(DisputeStatus.OPEN)
                        .resolutionNotes("Amount mismatch: Internal expected " + expectedAmount + ", bank reported " + record.getAmount())
                        .build();
                disputes.add(dispute);

                paymentEngineService.disputePayment(payment.getId(), "Amount mismatch detected in bank reconciliation");
            }
        });

        // 2. MISSING_FUNDS (Reverse Scan): Find internal CAPTURED or SETTLED payments in window absent from bank file
        List<Payment> settledOrCaptured = Collections.emptyList();
        if (windowStart != null || windowEnd != null) {
            settledOrCaptured = paymentRepository.findAll().stream()
                    .filter(p -> p.getState() == PaymentState.CAPTURED || p.getState() == PaymentState.SETTLED)
                    .filter(p -> p.getCapturedAt() != null)
                    .filter(p -> windowStart == null || !p.getCapturedAt().isBefore(windowStart))
                    .filter(p -> windowEnd == null || !p.getCapturedAt().isAfter(windowEnd))
                    .filter(p -> !matchedOrDisputedPaymentIds.contains(p.getId()))
                    .toList();
        }

        for (Payment missing : settledOrCaptured) {
            discrepancyCount[0]++;
            BigDecimal expAmount = missing.getCapturedAmount() != null ? missing.getCapturedAmount() : missing.getAmount();
            ReconciliationDispute dispute = ReconciliationDispute.builder()
                    .reconciliationBatch(currentBatch)
                    .payment(missing)
                    .bankReference(null)
                    .discrepancyType(DiscrepancyType.MISSING_FUNDS)
                    .expectedAmount(expAmount)
                    .actualBankAmount(BigDecimal.ZERO)
                    .discrepancyDifference(expAmount.negate())
                    .status(DisputeStatus.OPEN)
                    .resolutionNotes("Missing funds: Captured/settled payment missing from bank reconciliation statement.")
                    .build();
            disputes.add(dispute);

            paymentEngineService.disputePayment(missing.getId(), "Missing from bank settlement statement");
        }

        if (!disputes.isEmpty()) {
            reconciliationDisputeRepository.saveAll(disputes);
        }

        // 3. System-Wide Ledger Invariant Audit
        boolean ledgerBalanced = verifySystemLedgerDebitCreditInvariant();

        batch.setTotalBankRecords(totalRecords[0]);
        batch.setMatchedCount(matchedCount[0]);
        batch.setDiscrepancyCount(discrepancyCount[0]);
        batch.setLedgerDebitsCreditsBalanced(ledgerBalanced);
        batch.setCompletedAt(Instant.now());

        if (ledgerBalanced) {
            batch.setStatus(ReconciliationBatchStatus.COMPLETED);
            log.info("Reconciliation batch {} completed: {} matched, {} discrepancies, ledger balanced: true",
                    batchRef, matchedCount[0], discrepancyCount[0]);
        } else {
            batch.setStatus(ReconciliationBatchStatus.FAILED);
            log.error("Reconciliation batch {} FAILED: System-wide ledger debit/credit invariant violated!", batchRef);
        }

        return reconciliationBatchRepository.save(batch);
    }

    public ReconciliationBatch reconcileCsvContent(String sourceFileName, String csvContent) {
        return reconcileBankFile(sourceFileName, new StringReader(csvContent), null, null);
    }

    public ReconciliationBatch reconcileCsvContent(String sourceFileName, String csvContent, Instant windowStart, Instant windowEnd) {
        return reconcileBankFile(sourceFileName, new StringReader(csvContent), windowStart, windowEnd);
    }

    /**
     * Executes atomic mathematical audit across the entire PostgreSQL ledger_entries table:
     * Delta = Sum(DEBIT) - Sum(CREDIT/REFUND) == 0.00
     */
    public boolean verifySystemLedgerDebitCreditInvariant() {
        BigDecimal totalDebits = ledgerRepository.sumTotalDebits();
        BigDecimal totalCredits = ledgerRepository.sumTotalCredits();
        if (totalDebits == null) totalDebits = BigDecimal.ZERO;
        if (totalCredits == null) totalCredits = BigDecimal.ZERO;

        BigDecimal delta = totalDebits.subtract(totalCredits);
        return delta.compareTo(BigDecimal.ZERO) == 0;
    }

    private void publishReconciliationOutboxEvent(UUID paymentId, BigDecimal amount, String eventType, String message) {
        try {
            PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                    .paymentId(paymentId != null ? paymentId : UUID.randomUUID())
                    .amount(amount != null ? amount : BigDecimal.ZERO)
                    .status(eventType)
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventType("payment-notifications")
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize reconciliation outbox event", ex);
        }
    }
}
