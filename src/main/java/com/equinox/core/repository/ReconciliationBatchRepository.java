package com.equinox.core.repository;

import com.equinox.core.entity.ReconciliationBatch;
import com.equinox.core.entity.ReconciliationBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatch, UUID> {
    Optional<ReconciliationBatch> findByBatchReference(String batchReference);
    List<ReconciliationBatch> findByStatus(ReconciliationBatchStatus status);
}
