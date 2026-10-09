package com.equinox.core.repository;

import com.equinox.core.entity.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {
    List<SettlementItem> findBySettlementBatchId(UUID settlementBatchId);
    boolean existsByPaymentId(UUID paymentId);
}
