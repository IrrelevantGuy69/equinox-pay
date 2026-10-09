package com.equinox.core.repository;

import com.equinox.core.entity.SettlementBatch;
import com.equinox.core.entity.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {
    Optional<SettlementBatch> findByBatchReference(String batchReference);
    List<SettlementBatch> findByMerchantAccountIdAndStatus(UUID merchantAccountId, SettlementStatus status);
}
