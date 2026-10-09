package com.equinox.core.repository;

import com.equinox.core.entity.DiscrepancyType;
import com.equinox.core.entity.DisputeStatus;
import com.equinox.core.entity.ReconciliationDispute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationDisputeRepository extends JpaRepository<ReconciliationDispute, UUID> {
    List<ReconciliationDispute> findByReconciliationBatchId(UUID reconciliationBatchId);
    List<ReconciliationDispute> findByDiscrepancyType(DiscrepancyType discrepancyType);
    List<ReconciliationDispute> findByStatus(DisputeStatus status);
}
