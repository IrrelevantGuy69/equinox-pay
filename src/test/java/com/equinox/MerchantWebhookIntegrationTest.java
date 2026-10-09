package com.equinox;

import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.Account;
import com.equinox.core.entity.FailedWebhook;
import com.equinox.core.entity.Invoice;
import com.equinox.core.entity.Role;
import com.equinox.core.entity.User;
import com.equinox.core.repository.AccountRepository;
import com.equinox.core.repository.FailedWebhookRepository;
import com.equinox.core.repository.UserRepository;
import com.equinox.core.security.WebhookSecurityUtil;
import com.equinox.core.service.InvoiceService;
import com.equinox.core.service.WebhookDispatcherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MerchantWebhookIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private WebhookSecurityUtil webhookSecurityUtil;

    @Autowired
    private WebhookDispatcherService webhookDispatcherService;

    @Autowired
    private FailedWebhookRepository failedWebhookRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Invariant 5: Invoicing Engine generates Base64 URL-safe slug with 24h expiration")
    void testInvoiceSlugGenerationAndExpiration() {
        Account merchantAccount = accountRepository.save(Account.builder().balance(new BigDecimal("1000.00")).build());
        Invoice invoice = invoiceService.generateInvoice(merchantAccount.getId(), new BigDecimal("250.0000"));

        assertNotNull(invoice.getSlug());
        assertTrue(invoice.getSlug().startsWith("inv_"));
        // URL-safe characters only: letters, digits, '-', '_'
        assertTrue(invoice.getSlug().matches("^inv_[A-Za-z0-9_-]+$"), "Slug must be URL-safe Base64");

        assertEquals("PENDING", invoice.getStatus());
        assertNotNull(invoice.getExpiresAt());

        long hoursUntilExpiration = ChronoUnit.HOURS.between(invoice.getCreatedAt(), invoice.getExpiresAt());
        assertTrue(hoursUntilExpiration >= 23 && hoursUntilExpiration <= 24, "Invoice must expire in 24 hours");
    }

    @Test
    @DisplayName("Invariant 5: Cryptographic Webhook Engine signs payloads with HMAC-SHA256 (whsec_)")
    void testHmacSha256SignatureGeneration() {
        String payload = "{\"paymentId\":\"12345\",\"amount\":100.00,\"status\":\"SETTLED\"}";
        String secret = "whsec_test_secret_key_1234567890abcdef";

        String signature1 = webhookSecurityUtil.generateHmacSignature(payload, secret);
        assertNotNull(signature1);
        assertTrue(signature1.startsWith("v1="), "Signature must carry v1= scheme prefix");

        String signature2 = webhookSecurityUtil.generateHmacSignature(payload, secret);
        assertEquals(signature1, signature2, "HMAC-SHA256 must be deterministic");

        String tamperedSignature = webhookSecurityUtil.generateHmacSignature("tampered payload", secret);
        assertNotEquals(signature1, tamperedSignature, "Signature must differ on payload modification");
    }

    @Test
    @DisplayName("Invariant 5: Webhook Dispatcher routes unreachable merchant endpoints to DLQ")
    void testWebhookDispatcherDlqRouting() {
        Account merchantAccount = Account.builder().balance(BigDecimal.ZERO).build();
        User merchant = User.builder()
                .username("unreachable_merchant_" + UUID.randomUUID())
                .password("pwd")
                .role(Role.MERCHANT)
                .webhookUrl("http://localhost:59999/unreachable-endpoint")
                .webhookSecret("whsec_unreachable_secret")
                .account(merchantAccount)
                .build();
        merchant = userRepository.save(merchant);
        final UUID targetAccountId = merchant.getAccount().getId();

        PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                .paymentId(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .toAccountId(targetAccountId)
                .amount(new BigDecimal("100.00"))
                .status("SETTLED")
                .build();

        long dlqCountBefore = failedWebhookRepository.count();

        webhookDispatcherService.dispatchPaymentSuccessWebhook(event);

        List<FailedWebhook> failedWebhooks = failedWebhookRepository.findAll();
        assertTrue(failedWebhooks.size() > dlqCountBefore, "Failed webhook dispatch must be queued in DLQ");

        FailedWebhook recorded = failedWebhooks.stream()
                .filter(w -> w.getMerchantAccountId().equals(targetAccountId))
                .findFirst()
                .orElse(null);

        assertNotNull(recorded);
        assertEquals("PENDING_RETRY", recorded.getStatus());
        assertEquals(1, recorded.getAttemptCount());
        assertTrue(recorded.getNextRetryAt().isAfter(Instant.now()));
    }
}
