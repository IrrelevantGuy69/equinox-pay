package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private final KafkaTemplate<String, PaymentNotificationEvent> kafkaTemplate;

    public void sendPaymentSettledEvent(PaymentNotificationEvent event) {
        kafkaTemplate.send("payment-notifications", event.getPaymentId().toString(), event);
    }
}
