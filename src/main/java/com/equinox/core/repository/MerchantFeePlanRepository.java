package com.equinox.core.repository;

import com.equinox.core.entity.MerchantFeePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantFeePlanRepository extends JpaRepository<MerchantFeePlan, UUID> {
    Optional<MerchantFeePlan> findByMerchantAccountId(UUID merchantAccountId);
}
