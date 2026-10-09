package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.FailedWebhook;
import com.equinox.core.entity.User;
import com.equinox.core.repository.FailedWebhookRepository;
import com.equinox.core.repository.UserRepository;
import com.equinox.core.security.WebhookSecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class WebhookDispatcherService {

    // 1. Only one UserRepository needed!
    private final UserRepository userRepository;

    // 2. Here is the missing repository injection!
    private final FailedWebhookRepository failedWebhookRepository;

    private final WebhookSecurityUtil webhookSecurityUtil;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public void dispatchPaymentSuccessWebhook(PaymentNotificationEvent event) {
        // Find the merchant who is receiving the money
        User merchant = userRepository.findByAccountId(event.getToAccountId())
                .orElse(null);

        // If they don't exist or haven't set up webhooks, just skip it
        if (merchant == null || merchant.getWebhookUrl() == null || merchant.getWebhookSecret() == null) {
            System.out.println("Skipping webhook: Merchant not configured.");
            return;
        }

        String payload = null; // Declare outside so the catch block can see it!
        try {
            // Convert the event into a clean JSON string
            payload = objectMapper.writeValueAsString(event);

            // Generate the cryptographic signature
            String signature = webhookSecurityUtil.generateHmacSignature(payload, merchant.getWebhookSecret());

            // Build the HTTP Request with the signature in the header
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Equinox-Signature", signature);

            HttpEntity<String> request = new HttpEntity<>(payload, headers);

            // Fire it across the internet!
            restTemplate.postForEntity(merchant.getWebhookUrl(), request, String.class);
            System.out.println(">>> WEBHOOK FIRED SUCCESSFULLY TO: " + merchant.getWebhookUrl());

        } catch (Exception e) {
            System.err.println(">>> WEBHOOK DISPATCH FAILED: " + e.getMessage());
            System.out.println(">>> ROUTING TO DEAD LETTER QUEUE...");

            // Fallback just in case Jackson mapping failed
            String finalPayload = (payload != null) ? payload : "{\"error\": \"Could not serialize event\"}";

            FailedWebhook failedWebhook = FailedWebhook.builder()
                    .merchantAccountId(merchant.getAccount().getId())
                    .payload(finalPayload)
                    .attemptCount(1)
                    .nextRetryAt(Instant.now().plusSeconds(60)) // Try again in 1 minute
                    .status("PENDING_RETRY")
                    .build();

            failedWebhookRepository.save(failedWebhook);
        }
    }
}