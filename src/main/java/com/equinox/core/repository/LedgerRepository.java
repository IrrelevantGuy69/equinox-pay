package com.equinox.core.repository;

import com.equinox.core.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, UUID> {

    boolean existsByTransactionId(String transactionId);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.type = com.equinox.core.entity.EntryType.DEBIT")
    java.math.BigDecimal sumTotalDebits();

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.type IN (com.equinox.core.entity.EntryType.CREDIT, com.equinox.core.entity.EntryType.REFUND)")
    java.math.BigDecimal sumTotalCredits();
}
