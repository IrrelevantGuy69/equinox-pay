package com.equinox;

import com.equinox.core.document.PaymentDocument;
import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.Account;
import com.equinox.core.entity.OutboxEvent;
import com.equinox.core.entity.Payment;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.OutboxEventRepository;
import com.equinox.core.repository.PaymentSearchRepository;
import com.equinox.core.service.OutboxRelayService;
import com.equinox.core.service.PaymentEngineService;
import com.equinox.core.service.PaymentIndexerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OutboxAndKafkaCqrsTest {

    @Autowired
    private PaymentEngineService paymentEngineService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private PaymentIndexerService paymentIndexerService;

    @Autowired
    private PaymentSearchRepository paymentSearchRepository;

    @Test
    @DisplayName("Invariant 4: Transactional Outbox Pattern atomically persists outbox event upon payment settlement")
    void testTransactionalOutboxAtomicity() {
        Account a = accountRepository.save(Account.builder().balance(new BigDecimal("1000.00")).build());
        Account b = accountRepository.save(Account.builder().balance(new BigDecimal("500.00")).build());

        Payment payment = paymentEngineService.initializePayment(a.getId(), b.getId(), new BigDecimal("10.0000"));

        String idempotencyKey = "outbox_test_" + UUID.randomUUID();
        paymentEngineService.processPayment(payment.getId(), idempotencyKey);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAllByOrderByCreatedAtAsc();
        assertFalse(outboxEvents.isEmpty(), "Outbox event must be persisted atomically with settlement");

        OutboxEvent latest = outboxEvents.stream()
                .filter(e -> e.getPayload().contains(payment.getId().toString()))
                .findFirst()
                .orElse(null);

        assertNotNull(latest, "Outbox event matching payment must exist");
        assertEquals("payment-notifications", latest.getEventType());
    }

    @Test
    @DisplayName("Invariant 4: Asynchronous Outbox Relay publishes to Kafka and purges processed outbox records")
    void testOutboxRelayExecution() {
        Account a = accountRepository.save(Account.builder().balance(new BigDecimal("1000.00")).build());
        Account b = accountRepository.save(Account.builder().balance(new BigDecimal("500.00")).build());

        Payment payment = paymentEngineService.initializePayment(a.getId(), b.getId(), new BigDecimal("5.0000"));
        paymentEngineService.processPayment(payment.getId(), "relay_test_" + UUID.randomUUID());

        long countBefore = outboxEventRepository.count();
        assertTrue(countBefore > 0);

        outboxRelayService.processOutbox();

        long countAfter = outboxEventRepository.count();
        assertEquals(0, countAfter, "Outbox table must be drained after relay");
    }

    @Test
    @DisplayName("Invariant 4: CQRS Read Sync writes payment projection to Elasticsearch and queries successfully")
    void testElasticsearchCqrsSync() {
        UUID paymentId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                .paymentId(paymentId)
                .fromAccountId(from)
                .toAccountId(to)
                .amount(new BigDecimal("75.5000"))
                .status("SETTLED")
                .build();

        paymentIndexerService.indexPaymentToElasticsearch(event);

        List<PaymentDocument> history = paymentSearchRepository.findByFromAccountIdOrToAccountId(from.toString(), from.toString());
        assertFalse(history.isEmpty(), "Elasticsearch index must contain hydrated projection");
        assertTrue(history.stream().anyMatch(d -> d.getId().equals(paymentId.toString())));
    }
}
