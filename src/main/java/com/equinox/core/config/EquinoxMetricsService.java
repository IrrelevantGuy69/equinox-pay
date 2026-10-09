package com.equinox.core.config;

import com.equinox.core.entity.DisputeStatus;
import com.equinox.core.repository.OutboxEventRepository;
import com.equinox.core.repository.ReconciliationDisputeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EquinoxMetricsService {

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;
    private final ReconciliationDisputeRepository reconciliationDisputeRepository;

    private Counter successfulTransfersCounter;
    private Counter failedTransfersCounter;
    private Counter dlqMessagesCounter;
    private Timer transferTimer;
    private Timer captureTimer;

    @PostConstruct
    public void initMetrics() {
        successfulTransfersCounter = Counter.builder("equinox.vault.transfers.total")
                .tag("status", "success")
                .description("Total successful vault transfers")
                .register(meterRegistry);

        failedTransfersCounter = Counter.builder("equinox.vault.transfers.total")
                .tag("status", "failure")
                .description("Total failed vault transfers")
                .register(meterRegistry);

        dlqMessagesCounter = Counter.builder("equinox.kafka.dlq.count")
                .description("Total poison-pill messages routed to Dead Letter Queue")
                .register(meterRegistry);

        transferTimer = Timer.builder("equinox.vault.transfer.timer")
                .description("Latency distribution for vault transfers")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        captureTimer = Timer.builder("equinox.vault.capture.timer")
                .description("Latency distribution for payment captures")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Dynamic Gauge: count of pending outbox events
        meterRegistry.gauge("equinox.outbox.lag", outboxEventRepository,
                repo -> (double) repo.count());

        // Dynamic Gauge: count of open reconciliation disputes
        meterRegistry.gauge("equinox.reconciliation.disputes.open", reconciliationDisputeRepository,
                repo -> (double) repo.findByStatus(DisputeStatus.OPEN).size());
    }

    public void incrementTransferSuccess() {
        if (successfulTransfersCounter != null) successfulTransfersCounter.increment();
    }

    public void incrementTransferFailure() {
        if (failedTransfersCounter != null) failedTransfersCounter.increment();
    }

    public void incrementDlqCount() {
        if (dlqMessagesCounter != null) dlqMessagesCounter.increment();
    }

    public Timer getTransferTimer() {
        return transferTimer;
    }

    public Timer getCaptureTimer() {
        return captureTimer;
    }
}
