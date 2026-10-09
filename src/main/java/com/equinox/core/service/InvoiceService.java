package com.equinox.core.service;

import com.equinox.core.entity.Invoice;
import com.equinox.core.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentEngineService paymentEngineService;

    private static final SecureRandom secureRandom = new SecureRandom();

    public Invoice generateInvoice(UUID merchantAccountId, BigDecimal amount) {
        Invoice invoice = Invoice.builder()
                .slug(generateSecureSlug())
                .merchantAccountId(merchantAccountId)
                .amount(amount)
                .status("PENDING")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        return invoiceRepository.save(invoice);
    }

    private String generateSecureSlug() {
        byte[] randomBytes = new byte[9];
        secureRandom.nextBytes(randomBytes);
        String base64String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return "inv_" + base64String;
    }

    public Invoice getInvoiceBySlug(String slug) {
        return invoiceRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + slug));
    }

    @Transactional
    public Invoice payInvoice(String slug, UUID customerAccountId) {
        Invoice invoice = getInvoiceBySlug(slug);

        if (!"PENDING".equals(invoice.getStatus())) {
            throw new IllegalStateException("Invoice is already " + invoice.getStatus());
        }

        if (Instant.now().isAfter(invoice.getExpiresAt())) {
            invoice.setStatus("EXPIRED");
            invoiceRepository.save(invoice);
            throw new IllegalStateException("Invoice has expired");
        }

        paymentEngineService.transferFunds(customerAccountId, invoice.getMerchantAccountId(), invoice.getAmount());

        // SAVE THE CUSTOMER ID FOR POTENTIAL REFUNDS LATER!
        invoice.setCustomerAccountId(customerAccountId);
        invoice.setStatus("PAID");
        return invoiceRepository.save(invoice);
    }
    @Transactional
    public Invoice refundInvoice(String slug, UUID requestingMerchantAccountId) {
        Invoice invoice = getInvoiceBySlug(slug);

        // 1. Safety Check: Is it actually PAID?
        if (!"PAID".equals(invoice.getStatus())) {
            throw new IllegalStateException("Only PAID invoices can be refunded. Current status: " + invoice.getStatus());
        }

        // 2. Security Check: Does this merchant actually own this invoice?
        if (!invoice.getMerchantAccountId().equals(requestingMerchantAccountId)) {
            throw new SecurityException("You do not have permission to refund this invoice.");
        }

        // 3. We need to find the customer's Account ID.
        // In a full production app, you'd store the customerAccountId directly on the Invoice when they pay.
        // For now, since we only have two accounts interacting in our test, we will assume you have a way
        // to retrieve the original payer. Let's add a placeholder for the original customer ID.
        // *IMPORTANT*: You will need to add a `customerAccountId` field to your Invoice entity and save it during the `payInvoice` method!
        if (invoice.getCustomerAccountId() == null) {
            throw new IllegalStateException("Cannot refund: Original customer account is unknown.");
        }

        // 4. Fire the reverse engine!
        paymentEngineService.refundFunds(invoice.getMerchantAccountId(), invoice.getCustomerAccountId(), invoice.getAmount());

        // 5. Stamp it as REFUNDED
        invoice.setStatus("REFUNDED");
        return invoiceRepository.save(invoice);
    }

}
