package com.equinox.core.repository;

import com.equinox.core.entity.FailedWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface FailedWebhookRepository extends JpaRepository<FailedWebhook, UUID> {

    // Find all webhooks that are ready to be retried right now
    @Query("SELECT f FROM FailedWebhook f WHERE f.status = 'PENDING_RETRY' AND f.nextRetryAt <= :now")
    List<FailedWebhook> findWebhooksReadyForRetry(Instant now);
}
