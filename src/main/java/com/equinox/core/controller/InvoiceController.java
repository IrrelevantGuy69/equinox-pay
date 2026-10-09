package com.equinox.core.controller;

import com.equinox.core.dto.InvoiceRequest;
import com.equinox.core.entity.Invoice;
import com.equinox.core.entity.User;
import com.equinox.core.repository.UserRepository;
import com.equinox.core.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v2/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final UserRepository userRepository;

    @PostMapping("/generate")
    public ResponseEntity<Invoice> generateInvoice(@RequestBody InvoiceRequest request, Principal principal) {
        // Find the merchant who is currently logged in and making this request
        User merchant = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("Merchant not found"));

        // Generate the secure invoice linked to their account
        Invoice newInvoice = invoiceService.generateInvoice(merchant.getAccount().getId(), request.getAmount());

        return ResponseEntity.ok(newInvoice);
    }
    @GetMapping("/{slug}")
    public ResponseEntity<Invoice> getInvoiceDetails(@PathVariable String slug) {
        return ResponseEntity.ok(invoiceService.getInvoiceBySlug(slug));
    }
    @PostMapping("/{slug}/pay")
    public ResponseEntity<Invoice> payInvoice(@PathVariable String slug, Principal principal) {
        // Find the customer who is logged in and trying to pay
        User customer = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        // Execute the payment
        Invoice paidInvoice = invoiceService.payInvoice(slug, customer.getAccount().getId());

        return ResponseEntity.ok(paidInvoice);
    }
    @PostMapping("/{slug}/refund")
    public ResponseEntity<Invoice> refundInvoice(@PathVariable String slug, Principal principal) {
        User merchant = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("Merchant not found"));

        Invoice refundedInvoice = invoiceService.refundInvoice(slug, merchant.getAccount().getId());

        return ResponseEntity.ok(refundedInvoice);
    }
}
