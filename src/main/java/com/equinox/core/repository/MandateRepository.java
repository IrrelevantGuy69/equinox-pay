package com.equinox.core.repository;

import com.equinox.core.entity.Mandate;
import com.equinox.core.entity.MandateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MandateRepository extends JpaRepository<Mandate, UUID> {

    List<Mandate> findByStatusAndNextBillingDateBefore(MandateStatus status, Instant cutoff);

    List<Mandate> findByStatusAndNextBillingDateBetween(MandateStatus status, Instant start, Instant end);
}
