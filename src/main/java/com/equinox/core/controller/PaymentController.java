package com.equinox.core.controller;

import com.equinox.core.entity.Payment;
import com.equinox.core.service.FraudVelocityService;
import com.equinox.core.service.PaymentEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentEngineService paymentEngineService;
    private final FraudVelocityService fraudVelocityService;

    @PostMapping("/transfer")
    public ResponseEntity<?> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("from") UUID fromAccountId,
            @RequestParam("to") UUID toAccountId,
            @RequestParam("amount") BigDecimal amount) {

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive.");
        }
        if (fromAccountId == null || toAccountId == null) {
            throw new IllegalArgumentException("Source and destination accounts must be specified.");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account.");
        }

        // The Blast Shield: Check Redis before touching the database
        if (fraudVelocityService.isFraudulent(fromAccountId.toString(), idempotencyKey, amount)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    Map.of(
                            "error", "Velocity Limit Exceeded",
                            "message", "Transaction blocked due to suspicious activity.",
                            "status", 429
                    )
            );
        }

        // The Core Engine (Only executes if the shield passes)
        Payment payment = paymentEngineService.initializePayment(fromAccountId, toAccountId, amount);
        paymentEngineService.processPayment(payment.getId(), idempotencyKey);

        return ResponseEntity.ok(
                Map.of(
                        "status", "SETTLED",
                        "paymentId", payment.getId(),
                        "amount", amount
                )
        );
    }
}
