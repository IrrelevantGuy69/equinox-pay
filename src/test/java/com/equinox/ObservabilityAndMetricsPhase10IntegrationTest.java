package com.equinox;

import com.equinox.core.config.EquinoxMetricsService;
import com.equinox.core.entity.DisputeStatus;
import com.equinox.core.entity.DiscrepancyType;
import com.equinox.core.entity.ReconciliationBatch;
import com.equinox.core.entity.ReconciliationDispute;
import com.equinox.core.repository.ReconciliationBatchRepository;
import com.equinox.core.repository.ReconciliationDisputeRepository;
import com.equinox.core.security.TraceIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityAndMetricsPhase10IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TraceIdFilter traceIdFilter;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private EquinoxMetricsService metricsService;

    @Autowired
    private ReconciliationBatchRepository batchRepository;

    @Autowired
    private ReconciliationDisputeRepository disputeRepository;

    @Test
    @DisplayName("TraceIdFilter: Propagate incoming X-Correlation-ID to response header and MDC context")
    void testTraceIdFilterWithIncomingHeader() throws ServletException, IOException {
        String testCorrelationId = "test-corr-" + UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.CORRELATION_ID_HEADER, testCorrelationId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedTraceIdInMdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            capturedTraceIdInMdc.set(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY));
        };

        traceIdFilter.doFilter(request, response, chain);

        assertEquals(testCorrelationId, capturedTraceIdInMdc.get(), "MDC should contain the incoming correlationId");
        assertEquals(testCorrelationId, response.getHeader(TraceIdFilter.CORRELATION_ID_HEADER));
        assertEquals(testCorrelationId, response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
        assertNull(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY), "MDC should be cleared after filter completion");
    }

    @Test
    @DisplayName("TraceIdFilter: Generate new UUID correlation ID if header is missing")
    void testTraceIdFilterGeneratesNewId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedTraceIdInMdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            capturedTraceIdInMdc.set(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY));
        };

        traceIdFilter.doFilter(request, response, chain);

        assertNotNull(capturedTraceIdInMdc.get(), "MDC should contain generated traceId");
        assertNotNull(response.getHeader(TraceIdFilter.CORRELATION_ID_HEADER));
        assertEquals(capturedTraceIdInMdc.get(), response.getHeader(TraceIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Actuator & Prometheus: Verify custom business metrics registered and accessible")
    void testCustomMetersRegistration() {
        // 1. equinox.vault.transfers.total counter
        Counter successCounter = meterRegistry.find("equinox.vault.transfers.total")
                .tag("status", "success")
                .counter();
        assertNotNull(successCounter, "equinox.vault.transfers.total (success) counter must be registered");

        Counter failureCounter = meterRegistry.find("equinox.vault.transfers.total")
                .tag("status", "failure")
                .counter();
        assertNotNull(failureCounter, "equinox.vault.transfers.total (failure) counter must be registered");

        // 2. equinox.kafka.dlq.count counter
        Counter dlqCounter = meterRegistry.find("equinox.kafka.dlq.count").counter();
        assertNotNull(dlqCounter, "equinox.kafka.dlq.count counter must be registered");

        // 3. Timers
        Timer transferTimer = meterRegistry.find("equinox.vault.transfer.timer").timer();
        assertNotNull(transferTimer, "equinox.vault.transfer.timer must be registered");

        Timer captureTimer = meterRegistry.find("equinox.vault.capture.timer").timer();
        assertNotNull(captureTimer, "equinox.vault.capture.timer must be registered");

        // 4. Test counter increments
        double initialSuccess = successCounter.count();
        metricsService.incrementTransferSuccess();
        assertEquals(initialSuccess + 1.0, successCounter.count(), 0.001);

        double initialDlq = dlqCounter.count();
        metricsService.incrementDlqCount();
        assertEquals(initialDlq + 1.0, dlqCounter.count(), 0.001);
    }

    @Test
    @DisplayName("Actuator: Outbox lag and Dispute open gauges dynamic evaluation")
    void testDynamicGauges() {
        // Test equinox.outbox.lag gauge
        Gauge outboxGauge = meterRegistry.find("equinox.outbox.lag").gauge();
        assertNotNull(outboxGauge, "equinox.outbox.lag gauge must be registered");
        assertTrue(outboxGauge.value() >= 0.0);

        // Test equinox.reconciliation.disputes.open gauge
        Gauge disputeGauge = meterRegistry.find("equinox.reconciliation.disputes.open").gauge();
        assertNotNull(disputeGauge, "equinox.reconciliation.disputes.open gauge must be registered");

        double baselineOpenDisputes = disputeGauge.value();

        // Create an open dispute
        ReconciliationBatch batch = batchRepository.save(ReconciliationBatch.builder()
                .batchReference("TEST_BATCH_GAUGE_" + UUID.randomUUID())
                .sourceFileName("test_gauge.csv")
                .status(com.equinox.core.entity.ReconciliationBatchStatus.IN_PROGRESS)
                .startedAt(Instant.now())
                .build());

        ReconciliationDispute dispute = disputeRepository.save(ReconciliationDispute.builder()
                .reconciliationBatch(batch)
                .discrepancyType(DiscrepancyType.MISSING_FUNDS)
                .expectedAmount(new BigDecimal("100.0000"))
                .status(DisputeStatus.OPEN)
                .createdAt(Instant.now())
                .build());

        // Gauge should dynamically reflect the new count
        assertEquals(baselineOpenDisputes + 1.0, disputeGauge.value(), 0.001);
    }

    @Test
    @DisplayName("Actuator: Prometheus endpoint returns text/plain metrics scrape and handles correlation headers")
    void testPrometheusEndpointScrape() throws Exception {
        String testCorr = "http-corr-" + UUID.randomUUID();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .header(TraceIdFilter.CORRELATION_ID_HEADER, testCorr)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue(TraceIdFilter.CORRELATION_ID_HEADER).isPresent());
        assertEquals(testCorr, response.headers().firstValue(TraceIdFilter.CORRELATION_ID_HEADER).get());

        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("equinox_vault_transfers_total") ||
                   body.contains("equinox_outbox_lag") ||
                   body.contains("jvm_memory_used_bytes"),
                "Prometheus scrape should contain Equinox metrics or JVM metrics");
    }
}
