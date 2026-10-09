package com.equinox.core.service;

import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.entity.OutboxEvent;
import com.equinox.core.entity.Payment;
import com.equinox.core.entity.PaymentHistory;
import com.equinox.core.entity.PaymentState;
import com.equinox.core.repository.OutboxEventRepository;
import com.equinox.core.repository.PaymentHistoryRepository;
import com.equinox.core.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentEngineService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final VaultService vaultService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Payment initializePayment(UUID from, UUID to, BigDecimal amount) {
        Payment payment = Payment.builder()
                .fromAccountId(from)
                .toAccountId(to)
                .amount(amount)
                .state(PaymentState.PENDING)
                .build();

        payment = paymentRepository.save(payment);
        recordHistory(payment.getId(), null, PaymentState.PENDING);

        return payment;
    }

    public void processPayment(UUID paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        if (payment.getState() != PaymentState.PENDING) {
            throw new IllegalStateException("Payment is not in a PENDING state.");
        }

        transitionToProcessing(paymentId);

        try {
            settlePaymentAtomic(paymentId, idempotencyKey);
        } catch (RuntimeException e) {
            transitionToFailed(paymentId);
            throw e;
        } catch (Exception e) {
            transitionToFailed(paymentId);
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void transitionToProcessing(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        updatePaymentState(payment, PaymentState.PROCESSING);
    }

    @Transactional
    public void transitionToFailed(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment != null && payment.getState() != PaymentState.SETTLED) {
            updatePaymentState(payment, PaymentState.FAILED);
        }
    }

    @Transactional
    public void settlePaymentAtomic(UUID paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        vaultService.processTransfer(idempotencyKey, payment.getFromAccountId(), payment.getToAccountId(), payment.getAmount());
        updatePaymentState(payment, PaymentState.SETTLED);

        try {
            PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                    .paymentId(payment.getId())
                    .fromAccountId(payment.getFromAccountId())
                    .toAccountId(payment.getToAccountId())
                    .amount(payment.getAmount())
                    .status(payment.getState().name())
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventType("payment-notifications")
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize outbox event", ex);
        }
    }

    private void updatePaymentState(Payment payment, PaymentState newState) {
        PaymentState oldState = payment.getState();
        payment.setState(newState);
        paymentRepository.saveAndFlush(payment);
        recordHistory(payment.getId(), oldState, newState);
    }

    private void recordHistory(UUID paymentId, PaymentState oldState, PaymentState newState) {
        PaymentHistory history = PaymentHistory.builder()
                .paymentId(paymentId)
                .previousState(oldState)
                .newState(newState)
                .createdAt(LocalDateTime.now())
                .build();
        paymentHistoryRepository.save(history);
    }

    @Transactional
    public void resolveZombiePayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        if (payment.getState() == PaymentState.PROCESSING) {
            updatePaymentState(payment, PaymentState.FAILED);
        }
    }

    @Transactional
    public Payment disputePayment(UUID paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        updatePaymentState(payment, PaymentState.DISPUTED);
        publishOutboxEvent(payment, "PAYMENT_DISPUTED");
        return payment;
    }

    @Transactional
    public Payment authorizePayment(UUID payerAccountId, UUID merchantAccountId, BigDecimal amount, Instant authorizationExpiresAt) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Authorization amount must be strictly positive.");
        }
        String idempotencyKey = "auth_" + UUID.randomUUID();

        Payment payment = Payment.builder()
                .fromAccountId(payerAccountId)
                .toAccountId(merchantAccountId)
                .amount(amount)
                .authorizedAmount(amount)
                .capturedAmount(BigDecimal.ZERO)
                .refundedAmount(BigDecimal.ZERO)
                .authorizationExpiresAt(authorizationExpiresAt != null ? authorizationExpiresAt : Instant.now().plusSeconds(604800)) // 7 days default
                .state(PaymentState.AUTHORIZED)
                .build();

        vaultService.processAuthorize(idempotencyKey, payerAccountId, amount);
        payment = paymentRepository.save(payment);
        recordHistory(payment.getId(), null, PaymentState.AUTHORIZED);

        publishOutboxEvent(payment, "PAYMENT_AUTHORIZED");
        return payment;
    }

    @Transactional
    public Payment capturePayment(UUID paymentId, BigDecimal captureAmount, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getState() != PaymentState.AUTHORIZED) {
            throw new IllegalStateException("Payment is not in AUTHORIZED state. Current state: " + payment.getState());
        }

        if (payment.getAuthorizationExpiresAt() != null && Instant.now().isAfter(payment.getAuthorizationExpiresAt())) {
            throw new IllegalStateException("Authorization has expired");
        }

        if (captureAmount == null || captureAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Capture amount must be strictly positive");
        }

        if (captureAmount.compareTo(payment.getAuthorizedAmount()) > 0) {
            throw new IllegalArgumentException("Capture amount cannot exceed authorized amount");
        }

        VaultService.CaptureResult captureResult = vaultService.processCapture(idempotencyKey, payment.getFromAccountId(), payment.getToAccountId(),
                payment.getAuthorizedAmount(), captureAmount);

        payment.setCapturedAmount(captureAmount);
        payment.setFeeAmount(captureResult.fee());
        payment.setNetAmount(captureResult.netCredit());
        payment.setCapturedAt(Instant.now());
        updatePaymentState(payment, PaymentState.CAPTURED);

        publishOutboxEvent(payment, "PAYMENT_CAPTURED");
        return payment;
    }

    @Transactional
    public Payment voidPayment(UUID paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getState() != PaymentState.AUTHORIZED) {
            throw new IllegalStateException("Payment is not in AUTHORIZED state to void. Current state: " + payment.getState());
        }

        vaultService.processVoid(idempotencyKey, payment.getFromAccountId(), payment.getAuthorizedAmount());
        updatePaymentState(payment, PaymentState.VOIDED);

        publishOutboxEvent(payment, "PAYMENT_VOIDED");
        return payment;
    }

    @Transactional
    public Payment refundPaymentLifecycle(UUID paymentId, BigDecimal refundAmount, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getState() != PaymentState.CAPTURED && payment.getState() != PaymentState.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Cannot refund payment that is not CAPTURED or PARTIALLY_REFUNDED. Current state: " + payment.getState());
        }

        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be strictly positive");
        }

        BigDecimal currentRefunded = payment.getRefundedAmount() != null ? payment.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal newTotalRefunded = currentRefunded.add(refundAmount);

        if (newTotalRefunded.compareTo(payment.getCapturedAmount()) > 0) {
            throw new IllegalArgumentException("Total refunded amount (" + newTotalRefunded + ") cannot exceed captured amount (" + payment.getCapturedAmount() + ")");
        }

        // Ledger: DEBIT Merchant, DEBIT Platform Fees (proportional), CREDIT Payer with EntryType.REFUND
        vaultService.processRefund(idempotencyKey, payment.getToAccountId(), payment.getFromAccountId(),
                refundAmount, payment.getCapturedAmount(), payment.getFeeAmount());

        payment.setRefundedAmount(newTotalRefunded);
        PaymentState targetState = newTotalRefunded.compareTo(payment.getCapturedAmount()) == 0
                ? PaymentState.REFUNDED
                : PaymentState.PARTIALLY_REFUNDED;

        updatePaymentState(payment, targetState);
        publishOutboxEvent(payment, "PAYMENT_REFUNDED");
        return payment;
    }

    private void publishOutboxEvent(Payment payment, String eventType) {
        try {
            PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                    .paymentId(payment.getId())
                    .fromAccountId(payment.getFromAccountId())
                    .toAccountId(payment.getToAccountId())
                    .amount(payment.getAmount())
                    .status(eventType)
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventType("payment-notifications")
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize outbox event", ex);
        }
    }

    public void refundFunds(UUID merchantAccountId, UUID customerAccountId, BigDecimal amount) {
        String idempotencyKey = "ref_" + UUID.randomUUID().toString();

        Payment refundPayment = initializePayment(merchantAccountId, customerAccountId, amount);
        processRefundPayment(refundPayment.getId(), idempotencyKey);
    }

    public void processRefundPayment(UUID paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Refund payment not found"));

        transitionToProcessing(paymentId);

        try {
            settleRefundAtomic(paymentId, idempotencyKey);
        } catch (Exception e) {
            transitionToFailed(paymentId);
            throw new RuntimeException("Refund failed: " + e.getMessage());
        }
    }

    @Transactional
    public void settleRefundAtomic(UUID paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Refund payment not found"));

        vaultService.processTransfer(idempotencyKey, payment.getFromAccountId(), payment.getToAccountId(), payment.getAmount());
        updatePaymentState(payment, PaymentState.SETTLED);

        try {
            PaymentNotificationEvent event = PaymentNotificationEvent.builder()
                    .paymentId(payment.getId())
                    .fromAccountId(payment.getFromAccountId())
                    .toAccountId(payment.getToAccountId())
                    .amount(payment.getAmount())
                    .status("REFUNDED")
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventType("payment-notifications")
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize outbox event", ex);
        }
    }

    public void transferFunds(UUID customerAccountId, UUID merchantAccountId, BigDecimal amount) {
        String idempotencyKey = UUID.randomUUID().toString();
        Payment payment = initializePayment(customerAccountId, merchantAccountId, amount);
        processPayment(payment.getId(), idempotencyKey);
    }
}