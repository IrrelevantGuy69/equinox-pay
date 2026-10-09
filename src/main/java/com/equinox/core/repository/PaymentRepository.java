package com.equinox.core.repository;

import com.equinox.core.entity.Payment;
import com.equinox.core.entity.PaymentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByStateAndUpdatedAtBefore(PaymentState state, LocalDateTime cutoff);
    List<Payment> findByStateAndAuthorizationExpiresAtBefore(PaymentState state, java.time.Instant cutoff);
    List<Payment> findByStateAndClearedAtIsNullAndCapturedAtLessThanEqual(PaymentState state, java.time.Instant cutoff);
    List<Payment> findByToAccountIdAndStateAndClearedAtIsNotNull(UUID toAccountId, PaymentState state);
}
