package com.equinox.core.job;

import com.equinox.core.entity.Payment;
import com.equinox.core.entity.PaymentState;
import com.equinox.core.repository.PaymentRepository;
import com.equinox.core.service.PaymentEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutoVoidExpiredAuthorizationsJob {

    private final PaymentRepository paymentRepository;
    private final PaymentEngineService paymentEngineService;

    @Scheduled(fixedRate = 60000)
    public void voidExpiredAuthorizations() {
        Instant now = Instant.now();
        List<Payment> expiredPayments = paymentRepository.findByStateAndAuthorizationExpiresAtBefore(
                PaymentState.AUTHORIZED, now
        );

        if (!expiredPayments.isEmpty()) {
            log.info("Auto-voiding {} expired authorizations", expiredPayments.size());
        }

        for (Payment payment : expiredPayments) {
            try {
                String idempotencyKey = "autovoid_" + payment.getId() + "_" + UUID.randomUUID();
                paymentEngineService.voidPayment(payment.getId(), idempotencyKey);
                log.info("Successfully auto-voided expired payment {}", payment.getId());
            } catch (Exception e) {
                log.error("Failed to auto-void payment {}: {}", payment.getId(), e.getMessage());
            }
        }
    }
}
