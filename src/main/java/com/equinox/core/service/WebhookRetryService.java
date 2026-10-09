package com.equinox.core.service;

import com.equinox.core.entity.FailedWebhook;
import com.equinox.core.entity.User;
import com.equinox.core.repository.FailedWebhookRepository;
import com.equinox.core.repository.UserRepository;
import com.equinox.core.security.WebhookSecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WebhookRetryService {

    private final FailedWebhookRepository failedWebhookRepository;
    private final UserRepository userRepository;
    private final WebhookSecurityUtil webhookSecurityUtil;
    private final RestTemplate restTemplate;

    // Runs every 30 seconds
    @Scheduled(fixedDelay = 30000)
    public void processRetries() {
        List<FailedWebhook> webhooksToRetry = failedWebhookRepository.findWebhooksReadyForRetry(Instant.now());

        for (FailedWebhook webhook : webhooksToRetry) {
            User merchant = userRepository.findByAccountId(webhook.getMerchantAccountId()).orElse(null);

            if (merchant == null || merchant.getWebhookUrl() == null) {
                webhook.setStatus("DEAD");
                failedWebhookRepository.save(webhook);
                continue;
            }

            try {
                System.out.println(">>> ATTEMPTING WEBHOOK RETRY (" + webhook.getAttemptCount() + ") for " + merchant.getUsername());

                String signature = webhookSecurityUtil.generateHmacSignature(webhook.getPayload(), merchant.getWebhookSecret());

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Equinox-Signature", signature);

                HttpEntity<String> request = new HttpEntity<>(webhook.getPayload(), headers);
                restTemplate.postForEntity(merchant.getWebhookUrl(), request, String.class);

                // If it succeeds, delete it from the queue!
                System.out.println(">>> RETRY SUCCESSFUL! Removing from DLQ.");
                failedWebhookRepository.delete(webhook);

            } catch (Exception e) {
                System.err.println(">>> RETRY FAILED: " + e.getMessage());

                webhook.setAttemptCount(webhook.getAttemptCount() + 1);

                // Exponential backoff: 1 min, 5 mins, 15 mins, etc.
                long backoffSeconds = (long) Math.pow(5, webhook.getAttemptCount()) * 12;
                webhook.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));

                if (webhook.getAttemptCount() >= 5) {
                    System.err.println(">>> MAX RETRIES REACHED. MARKING WEBHOOK DEAD.");
                    webhook.setStatus("DEAD");
                }

                failedWebhookRepository.save(webhook);
            }
        }
    }
}
