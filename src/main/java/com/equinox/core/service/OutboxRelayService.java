package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.OutboxEvent;
import com.equinox.core.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void processOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findAllByOrderByCreatedAtAsc();

        // Only print to the terminal IF there are actually events to process
        if (!events.isEmpty()) {
            System.out.println(">>> OUTBOX SWEEP: Relaying " + events.size() + " guaranteed events to Kafka");
        }

        for (OutboxEvent event : events) {
            String topic = event.getEventType();
            if (topic == null || topic.isBlank() || "payment-settled".equals(topic) || "payment-refunded".equals(topic)) {
                topic = "payment-notifications";
            }

            String traceId = org.slf4j.MDC.get("traceId");
            if (traceId == null || traceId.isBlank()) {
                traceId = java.util.UUID.randomUUID().toString();
            }

            try {
                PaymentNotificationEvent eventObj = objectMapper.readValue(event.getPayload(), PaymentNotificationEvent.class);
                String key = eventObj.getPaymentId() != null ? eventObj.getPaymentId().toString() : null;
                org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                        new org.apache.kafka.clients.producer.ProducerRecord<>(topic, key, eventObj);
                record.headers().add("X-Trace-Id", traceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                kafkaTemplate.send(record);
            } catch (Exception e) {
                // If not deserializable into PaymentNotificationEvent, send raw payload string
                org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                        new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, event.getPayload());
                record.headers().add("X-Trace-Id", traceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                kafkaTemplate.send(record);
            }

            outboxEventRepository.delete(event);
        }
    }
}
