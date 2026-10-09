package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationListener {

    private final WebhookDispatcherService webhookDispatcherService;

    @KafkaListener(topics = "payment-notifications", groupId = "equinox-notification-group")
    public void handlePaymentNotification(PaymentNotificationEvent event) {
        System.out.println("==================================================");
        System.out.println("ASYNC EVENT RECEIVED - BACKGROUND WORKER INITIATED");
        System.out.println("Payment ID: " + event.getPaymentId());
        System.out.println("Transferred: $" + event.getAmount());
        System.out.println("From Vault: " + event.getFromAccountId());
        System.out.println("To Vault: " + event.getToAccountId());
        System.out.println("Simulating Email Dispatch...");
        System.out.println("Status: EMAIL SENT SUCCESSFULLY");
        System.out.println("==================================================");

        webhookDispatcherService.dispatchPaymentSuccessWebhook(event);
    }
}