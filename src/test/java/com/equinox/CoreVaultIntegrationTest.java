package com.equinox;

import com.equinox.core.entity.Account;
import com.equinox.core.entity.EntryType;
import com.equinox.core.entity.LedgerEntry;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.LedgerRepository;
import com.equinox.core.service.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CoreVaultIntegrationTest {

    @Autowired
    private VaultService vaultService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

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
    @DisplayName("Invariant 1: Double-Entry Ledger writes balanced DEBIT and CREDIT entries")
    void testDoubleEntryLedgerBalance() {
        String txnId = "txn_" + UUID.randomUUID();
        BigDecimal transferAmount = new BigDecimal("150.0000");

        vaultService.processTransfer(txnId, accountAId, accountBId, transferAmount);

        Account a = accountRepository.findById(accountAId).orElseThrow();
        Account b = accountRepository.findById(accountBId).orElseThrow();

        assertEquals(new BigDecimal("850.0000"), a.getBalance());
        assertEquals(new BigDecimal("650.0000"), b.getBalance());

        assertTrue(ledgerRepository.existsByTransactionId(txnId));

        List<LedgerEntry> entries = ledgerRepository.findAll().stream()
                .filter(e -> txnId.equals(e.getTransactionId()))
                .toList();

        assertEquals(2, entries.size());

        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(totalDebits, totalCredits, "Sum of Debits must equal Sum of Credits");
        assertEquals(transferAmount, totalDebits);
    }

    @Test
    @DisplayName("Invariant 1: Strict Positive Amount Validation")
    void testStrictPositiveAmountValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                vaultService.processTransfer("txn_zero", accountAId, accountBId, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () ->
                vaultService.processTransfer("txn_neg", accountAId, accountBId, new BigDecimal("-50.00")));
    }

    @Test
    @DisplayName("Invariant 1: Self Transfer Rejection")
    void testSelfTransferRejection() {
        assertThrows(IllegalArgumentException.class, () ->
                vaultService.processTransfer("txn_self", accountAId, accountAId, new BigDecimal("10.00")));
    }

    @Test
    @DisplayName("Invariant 1: Idempotency Key Replay Protection")
    void testIdempotencyReplay() {
        String txnId = "idemp_" + UUID.randomUUID();
        vaultService.processTransfer(txnId, accountAId, accountBId, new BigDecimal("25.0000"));

        assertThrows(IllegalStateException.class, () ->
                vaultService.processTransfer(txnId, accountAId, accountBId, new BigDecimal("25.0000")));
    }

    @Test
    @DisplayName("Invariant 1: Deadlock-Free Bidirectional Concurrent Transfers")
    void testDeadlockFreeConcurrentTransfers() throws InterruptedException, ExecutionException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                latch.await();
                String key = "conc_" + UUID.randomUUID();
                if (index % 2 == 0) {
                    vaultService.processTransfer(key, accountAId, accountBId, new BigDecimal("5.0000"));
                } else {
                    vaultService.processTransfer(key, accountBId, accountAId, new BigDecimal("5.0000"));
                }
                return true;
            }));
        }

        latch.countDown();
        for (Future<Boolean> future : futures) {
            assertTrue(future.get());
        }
        executor.shutdown();
    }
}
