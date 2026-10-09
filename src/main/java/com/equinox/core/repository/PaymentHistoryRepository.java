package com.equinox.core.repository;

import com.equinox.core.entity.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, UUID> {
    List<PaymentHistory> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
