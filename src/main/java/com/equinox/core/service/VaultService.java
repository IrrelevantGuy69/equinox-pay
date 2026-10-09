package com.equinox.core.service;

import com.equinox.core.dto.SplitLeg;
import com.equinox.core.entity.*;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.LedgerRepository;
import com.equinox.core.repository.MerchantFeePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VaultService {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final MerchantFeePlanRepository merchantFeePlanRepository;
    private final com.equinox.core.config.EquinoxMetricsService metricsService;

    public static final UUID HOLD_SUSPENSE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    public static final UUID PLATFORM_FEES_ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID PAYOUT_TRANSIT_ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    public record CaptureResult(BigDecimal fee, BigDecimal netCredit) {}

    @Transactional
    public void processTransfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        long start = System.nanoTime();
        try {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Transfer amount must be strictly positive.");
            }

            if (fromAccountId.equals(toAccountId)) {
                throw new IllegalArgumentException("Cannot transfer funds to the same account.");
            }

            if (ledgerRepository.existsByTransactionId(idempotencyKey)) {
                throw new IllegalStateException("Transaction already processed.");
            }

            UUID firstLock = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
            UUID secondLock = fromAccountId.compareTo(toAccountId) < 0 ? toAccountId : fromAccountId;

            Account firstAccount = accountRepository.findByIdForUpdate(firstLock)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLock));
            Account secondAccount = accountRepository.findByIdForUpdate(secondLock)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLock));

            Account fromAccount = fromAccountId.equals(firstLock) ? firstAccount : secondAccount;
            Account toAccount = toAccountId.equals(firstLock) ? firstAccount : secondAccount;

            if (fromAccount.getBalance().compareTo(amount) < 0) {
                throw new IllegalStateException("Insufficient funds");
            }

            fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
            toAccount.setBalance(toAccount.getBalance().add(amount));

            LedgerEntry debit = LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(fromAccount)
                    .amount(amount)
                    .type(EntryType.DEBIT)
                    .build();

            LedgerEntry credit = LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(toAccount)
                    .amount(amount)
                    .type(EntryType.CREDIT)
                    .build();

            ledgerRepository.saveAll(List.of(debit, credit));
            accountRepository.saveAll(List.of(fromAccount, toAccount));

            if (metricsService != null) {
                metricsService.incrementTransferSuccess();
                if (metricsService.getTransferTimer() != null) {
                    metricsService.getTransferTimer().record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
                }
            }
        } catch (Exception ex) {
            if (metricsService != null) {
                metricsService.incrementTransferFailure();
            }
            throw ex;
        }
    }

    /**
     * Phase 6.1: Authorize (Hold funds)
     * DEBIT Payer Account, CREDIT Hold Suspense Account
     */
    @Transactional
    public void processAuthorize(String idempotencyKey, UUID payerAccountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Authorization amount must be strictly positive.");
        }
        if (payerAccountId.equals(HOLD_SUSPENSE_ACCOUNT_ID)) {
            throw new IllegalArgumentException("Payer cannot be the Hold Suspense Account.");
        }
        if (ledgerRepository.existsByTransactionId(idempotencyKey)) {
            throw new IllegalStateException("Authorization already processed: " + idempotencyKey);
        }

        UUID firstLock = payerAccountId.compareTo(HOLD_SUSPENSE_ACCOUNT_ID) < 0 ? payerAccountId : HOLD_SUSPENSE_ACCOUNT_ID;
        UUID secondLock = payerAccountId.compareTo(HOLD_SUSPENSE_ACCOUNT_ID) < 0 ? HOLD_SUSPENSE_ACCOUNT_ID : payerAccountId;

        Account firstAccount = accountRepository.findByIdForUpdate(firstLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLock));
        Account secondAccount = accountRepository.findByIdForUpdate(secondLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLock));

        Account payer = payerAccountId.equals(firstLock) ? firstAccount : secondAccount;
        Account suspense = HOLD_SUSPENSE_ACCOUNT_ID.equals(firstLock) ? firstAccount : secondAccount;

        if (payer.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds for authorization");
        }

        payer.setBalance(payer.getBalance().subtract(amount));
        suspense.setBalance(suspense.getBalance().add(amount));

        LedgerEntry debit = LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(payer)
                .amount(amount)
                .type(EntryType.DEBIT)
                .build();

        LedgerEntry credit = LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(suspense)
                .amount(amount)
                .type(EntryType.CREDIT)
                .build();

        ledgerRepository.saveAll(List.of(debit, credit));
        accountRepository.saveAll(List.of(payer, suspense));
    }

    /**
     * Phase 6 & Phase 8: Full and Partial Capture with automated fee deduction.
     * Full Capture:
     *   DEBIT Hold Suspense (capturedAmount)
     *   CREDIT Merchant pendingBalance (netCredit)
     *   CREDIT Platform Fees availableBalance (fee)
     * Partial Capture:
     *   DEBIT Hold Suspense (authorizedAmount)
     *   CREDIT Merchant pendingBalance (netCredit)
     *   CREDIT Platform Fees availableBalance (fee)
     *   CREDIT Payer availableBalance (unreservedAmount)
     */
    @Transactional
    public CaptureResult processCapture(String idempotencyKey, UUID payerAccountId, UUID merchantAccountId,
                                BigDecimal authorizedAmount, BigDecimal capturedAmount) {
        long startCapture = System.nanoTime();
        if (capturedAmount == null || capturedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Capture amount must be strictly positive.");
        }
        if (capturedAmount.compareTo(authorizedAmount) > 0) {
            throw new IllegalArgumentException("Captured amount cannot exceed authorized amount.");
        }
        if (ledgerRepository.existsByTransactionId(idempotencyKey)) {
            throw new IllegalStateException("Capture already processed: " + idempotencyKey);
        }

        BigDecimal unreservedAmount = authorizedAmount.subtract(capturedAmount);
        boolean isPartial = unreservedAmount.compareTo(BigDecimal.ZERO) > 0;

        // Calculate fee if merchant has fee plan
        BigDecimal fee = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN);
        Optional<MerchantFeePlan> feePlanOpt = merchantFeePlanRepository.findByMerchantAccountId(merchantAccountId);
        if (feePlanOpt.isPresent()) {
            fee = feePlanOpt.get().calculateFee(capturedAmount);
        }
        BigDecimal netCredit = capturedAmount.subtract(fee);

        // Collect all distinct accounts to lock in deterministic order
        Set<UUID> accountIdsToLock = new HashSet<>();
        accountIdsToLock.add(HOLD_SUSPENSE_ACCOUNT_ID);
        accountIdsToLock.add(merchantAccountId);
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            accountIdsToLock.add(PLATFORM_FEES_ACCOUNT_ID);
        }
        if (isPartial) {
            accountIdsToLock.add(payerAccountId);
        }

        List<UUID> sortedLocks = accountIdsToLock.stream().sorted().toList();
        Map<UUID, Account> lockedAccounts = new HashMap<>();
        for (UUID lockId : sortedLocks) {
            Account acc = accountRepository.findByIdForUpdate(lockId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + lockId));
            lockedAccounts.put(lockId, acc);
        }

        Account suspense = lockedAccounts.get(HOLD_SUSPENSE_ACCOUNT_ID);
        Account merchant = lockedAccounts.get(merchantAccountId);
        Account payer = lockedAccounts.get(payerAccountId);
        Account platformFees = lockedAccounts.get(PLATFORM_FEES_ACCOUNT_ID);

        if (suspense.getBalance().compareTo(authorizedAmount) < 0) {
            throw new IllegalStateException("Hold suspense account has insufficient balance for capture");
        }

        suspense.setBalance(suspense.getBalance().subtract(authorizedAmount));
        merchant.creditPending(netCredit);

        List<LedgerEntry> entries = new ArrayList<>();
        List<Account> accountsToSave = new ArrayList<>();
        accountsToSave.add(suspense);
        accountsToSave.add(merchant);

        // Debit Hold Suspense for the authorizedAmount
        entries.add(LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(suspense)
                .amount(authorizedAmount)
                .type(EntryType.DEBIT)
                .build());

        // Credit Merchant for netCredit
        entries.add(LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(merchant)
                .amount(netCredit)
                .type(EntryType.CREDIT)
                .build());

        // Credit Platform Fees account if fee > 0
        if (fee.compareTo(BigDecimal.ZERO) > 0 && platformFees != null) {
            platformFees.creditAvailable(fee);
            accountsToSave.add(platformFees);
            entries.add(LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(platformFees)
                    .amount(fee)
                    .type(EntryType.CREDIT)
                    .build());
        }

        if (isPartial && payer != null) {
            payer.creditAvailable(unreservedAmount);
            accountsToSave.add(payer);

            // Credit Payer for unreserved hold release
            entries.add(LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(payer)
                    .amount(unreservedAmount)
                    .type(EntryType.CREDIT)
                    .build());
        }

        ledgerRepository.saveAll(entries);
        accountRepository.saveAll(accountsToSave);

        if (metricsService != null && metricsService.getCaptureTimer() != null) {
            metricsService.getCaptureTimer().record(System.nanoTime() - startCapture, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        return new CaptureResult(fee, netCredit);
    }

    /**
     * Phase 6.1: Void (Cancel hold)
     * DEBIT Hold Suspense (authorizedAmount), CREDIT Payer (authorizedAmount)
     */
    @Transactional
    public void processVoid(String idempotencyKey, UUID payerAccountId, BigDecimal authorizedAmount) {
        if (authorizedAmount == null || authorizedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Void amount must be strictly positive.");
        }
        if (ledgerRepository.existsByTransactionId(idempotencyKey)) {
            throw new IllegalStateException("Void already processed: " + idempotencyKey);
        }

        UUID firstLock = payerAccountId.compareTo(HOLD_SUSPENSE_ACCOUNT_ID) < 0 ? payerAccountId : HOLD_SUSPENSE_ACCOUNT_ID;
        UUID secondLock = payerAccountId.compareTo(HOLD_SUSPENSE_ACCOUNT_ID) < 0 ? HOLD_SUSPENSE_ACCOUNT_ID : payerAccountId;

        Account firstAccount = accountRepository.findByIdForUpdate(firstLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLock));
        Account secondAccount = accountRepository.findByIdForUpdate(secondLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLock));

        Account payer = payerAccountId.equals(firstLock) ? firstAccount : secondAccount;
        Account suspense = HOLD_SUSPENSE_ACCOUNT_ID.equals(firstLock) ? firstAccount : secondAccount;

        if (suspense.getBalance().compareTo(authorizedAmount) < 0) {
            throw new IllegalStateException("Hold suspense account has insufficient balance for void");
        }

        suspense.setBalance(suspense.getBalance().subtract(authorizedAmount));
        payer.creditAvailable(authorizedAmount);

        LedgerEntry debit = LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(suspense)
                .amount(authorizedAmount)
                .type(EntryType.DEBIT)
                .build();

        LedgerEntry credit = LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(payer)
                .amount(authorizedAmount)
                .type(EntryType.CREDIT)
                .build();

        ledgerRepository.saveAll(List.of(debit, credit));
        accountRepository.saveAll(List.of(suspense, payer));
    }

    /**
     * Phase 6.1 & Phase 8.3: Refund / Partial Refund with Proportional Fee Reversal
     */
    @Transactional
    public void processRefund(String idempotencyKey, UUID merchantAccountId, UUID payerAccountId, BigDecimal refundAmount) {
        processRefund(idempotencyKey, merchantAccountId, payerAccountId, refundAmount, null, null);
    }

    @Transactional
    public void processRefund(String idempotencyKey, UUID merchantAccountId, UUID payerAccountId,
                              BigDecimal refundAmount, BigDecimal capturedAmount, BigDecimal originalFee) {
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be strictly positive.");
        }
        if (merchantAccountId.equals(payerAccountId)) {
            throw new IllegalArgumentException("Cannot refund to the same account.");
        }
        if (ledgerRepository.existsByTransactionId(idempotencyKey)) {
            throw new IllegalStateException("Refund already processed: " + idempotencyKey);
        }

        // Calculate proportional fee reversal
        BigDecimal feeReversal = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN);
        if (originalFee != null && originalFee.compareTo(BigDecimal.ZERO) > 0 && capturedAmount != null && capturedAmount.compareTo(BigDecimal.ZERO) > 0) {
            feeReversal = refundAmount.multiply(originalFee).divide(capturedAmount, 4, RoundingMode.HALF_EVEN);
        }
        BigDecimal merchantDebit = refundAmount.subtract(feeReversal);

        // Lock ordering for dynamic N parties: merchant, payer, and platform fees (if feeReversal > 0)
        Set<UUID> accountIdsToLock = new HashSet<>();
        accountIdsToLock.add(merchantAccountId);
        accountIdsToLock.add(payerAccountId);
        if (feeReversal.compareTo(BigDecimal.ZERO) > 0) {
            accountIdsToLock.add(PLATFORM_FEES_ACCOUNT_ID);
        }

        List<UUID> sortedLocks = accountIdsToLock.stream().sorted().toList();
        Map<UUID, Account> lockedAccounts = new HashMap<>();
        for (UUID lockId : sortedLocks) {
            Account acc = accountRepository.findByIdForUpdate(lockId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + lockId));
            lockedAccounts.put(lockId, acc);
        }

        Account merchant = lockedAccounts.get(merchantAccountId);
        Account payer = lockedAccounts.get(payerAccountId);
        Account platformFees = lockedAccounts.get(PLATFORM_FEES_ACCOUNT_ID);

        if (merchant.getBalance().compareTo(merchantDebit) < 0) {
            throw new IllegalStateException("Merchant has insufficient funds for refund");
        }

        // Debit merchant
        if (merchant.getAvailableBalance() != null && merchant.getAvailableBalance().compareTo(merchantDebit) >= 0) {
            merchant.debitAvailable(merchantDebit);
        } else {
            merchant.setBalance(merchant.getBalance().subtract(merchantDebit));
        }

        // Debit platform fees if proportional fee reversal
        if (feeReversal.compareTo(BigDecimal.ZERO) > 0 && platformFees != null) {
            platformFees.debitAvailable(feeReversal);
        }

        // Credit payer
        payer.creditAvailable(refundAmount);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(merchant)
                .amount(merchantDebit)
                .type(EntryType.DEBIT)
                .build());

        if (feeReversal.compareTo(BigDecimal.ZERO) > 0 && platformFees != null) {
            entries.add(LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(platformFees)
                    .amount(feeReversal)
                    .type(EntryType.DEBIT)
                    .build());
        }

        entries.add(LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(payer)
                .amount(refundAmount)
                .type(EntryType.REFUND)
                .build());

        ledgerRepository.saveAll(entries);
        accountRepository.saveAll(lockedAccounts.values());
    }

    /**
     * Phase 8.2: Dynamic N-Party Split Payments
     * Marketplace checkouts: 1 buyer, N merchants, 1 platform fees account.
     * Deadlock-free sorted UUID lock ordering.
     */
    @Transactional
    public void processSplitTransfer(String idempotencyKey, UUID payerId, List<SplitLeg> splits) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key cannot be empty.");
        }
        if (ledgerRepository.existsByTransactionId(idempotencyKey)) {
            throw new IllegalStateException("Split transaction already processed: " + idempotencyKey);
        }
        if (payerId == null) {
            throw new IllegalArgumentException("Payer account ID cannot be null.");
        }
        if (splits == null || splits.isEmpty()) {
            throw new IllegalArgumentException("Split legs cannot be empty.");
        }

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;

        record EvaluatedLeg(UUID merchantId, BigDecimal grossAmount, BigDecimal fee, BigDecimal netAmount) {}
        List<EvaluatedLeg> evaluatedLegs = new ArrayList<>();

        for (SplitLeg leg : splits) {
            if (leg.getAmount() == null || leg.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Split amount must be strictly positive.");
            }
            if (leg.getMerchantAccountId() == null) {
                throw new IllegalArgumentException("Merchant account ID cannot be null.");
            }
            if (leg.getMerchantAccountId().equals(payerId)) {
                throw new IllegalArgumentException("Payer and merchant cannot be the same account.");
            }

            BigDecimal fee = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN);
            if (leg.getCustomFee() != null) {
                fee = leg.getCustomFee().setScale(4, RoundingMode.HALF_EVEN);
            } else {
                Optional<MerchantFeePlan> planOpt = merchantFeePlanRepository.findByMerchantAccountId(leg.getMerchantAccountId());
                if (planOpt.isPresent()) {
                    fee = planOpt.get().calculateFee(leg.getAmount());
                }
            }

            BigDecimal net = leg.getAmount().subtract(fee);
            if (net.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Fee exceeds gross split amount for merchant " + leg.getMerchantAccountId());
            }

            totalGross = totalGross.add(leg.getAmount());
            totalFees = totalFees.add(fee);
            evaluatedLegs.add(new EvaluatedLeg(leg.getMerchantAccountId(), leg.getAmount(), fee, net));
        }

        // Dynamic N-Party Lock Ordering:
        Set<UUID> accountIdsToLock = new HashSet<>();
        accountIdsToLock.add(payerId);
        for (EvaluatedLeg leg : evaluatedLegs) {
            accountIdsToLock.add(leg.merchantId());
        }
        if (totalFees.compareTo(BigDecimal.ZERO) > 0) {
            accountIdsToLock.add(PLATFORM_FEES_ACCOUNT_ID);
        }

        List<UUID> sortedLocks = accountIdsToLock.stream().sorted().toList();
        Map<UUID, Account> lockedAccounts = new HashMap<>();
        for (UUID lockId : sortedLocks) {
            Account acc = accountRepository.findByIdForUpdate(lockId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + lockId));
            lockedAccounts.put(lockId, acc);
        }

        Account payer = lockedAccounts.get(payerId);
        if (payer.getBalance().compareTo(totalGross) < 0) {
            throw new IllegalStateException("Payer has insufficient funds for split payment. Available: "
                    + payer.getBalance() + ", Required: " + totalGross);
        }

        payer.debitAvailable(totalGross);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(LedgerEntry.builder()
                .transactionId(idempotencyKey)
                .account(payer)
                .amount(totalGross)
                .type(EntryType.DEBIT)
                .build());

        for (EvaluatedLeg leg : evaluatedLegs) {
            Account merchant = lockedAccounts.get(leg.merchantId());
            merchant.creditPending(leg.netAmount());
            entries.add(LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(merchant)
                    .amount(leg.netAmount())
                    .type(EntryType.CREDIT)
                    .build());
        }

        if (totalFees.compareTo(BigDecimal.ZERO) > 0) {
            Account platformFeesAccount = lockedAccounts.get(PLATFORM_FEES_ACCOUNT_ID);
            platformFeesAccount.creditAvailable(totalFees);
            entries.add(LedgerEntry.builder()
                    .transactionId(idempotencyKey)
                    .account(platformFeesAccount)
                    .amount(totalFees)
                    .type(EntryType.CREDIT)
                    .build());
        }

        ledgerRepository.saveAll(entries);
        accountRepository.saveAll(lockedAccounts.values());
    }
}