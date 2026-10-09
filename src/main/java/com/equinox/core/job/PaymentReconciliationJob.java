package com.equinox.core.job;

import com.equinox.core.entity.Payment;
import com.equinox.core.entity.PaymentState;
import com.equinox.core.repository.PaymentRepository;
import com.equinox.core.service.PaymentEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentReconciliationJob {

    private final PaymentRepository paymentRepository;
    private final PaymentEngineService paymentEngineService;

    @Scheduled(fixedRate = 60000)
    public void sweepZombiePayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(60);
        List<Payment> zombies = paymentRepository.findByStateAndUpdatedAtBefore(PaymentState.PROCESSING, cutoff);

        for (Payment zombie : zombies) {
            paymentEngineService.resolveZombiePayment(zombie.getId());
        }
    }
}
