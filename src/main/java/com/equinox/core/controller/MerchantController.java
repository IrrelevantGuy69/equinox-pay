package com.equinox.core.controller;

import com.equinox.core.dto.WebhookConfigRequest;
import com.equinox.core.entity.User;
import com.equinox.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final UserRepository userRepository;

    @PostMapping("/webhook-config")
    public ResponseEntity<?> configureWebhook(@RequestBody WebhookConfigRequest request, Principal principal) {
        User merchant = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("Merchant not found"));

        // Update their URL
        merchant.setWebhookUrl(request.getWebhookUrl());

        // If they don't have a secret yet, generate a secure one for them!
        if (merchant.getWebhookSecret() == null) {
            String generatedSecret = "whsec_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().substring(0, 8);
            merchant.setWebhookSecret(generatedSecret);
        }

        userRepository.save(merchant);

        // Return the secret so the merchant can save it in their own .env file
        return ResponseEntity.ok(Map.of(
                "message", "Webhook URL configured successfully",
                "webhookUrl", merchant.getWebhookUrl(),
                "webhookSecret", merchant.getWebhookSecret()
        ));
    }
}
