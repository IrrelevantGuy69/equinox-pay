package com.equinox.core.job;

import com.equinox.core.entity.Mandate;
import com.equinox.core.entity.MandateStatus;
import com.equinox.core.repository.MandateRepository;
import com.equinox.core.service.MandateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringBillingJob {

    private final MandateService mandateService;

    /**
     * Executes recurring billing cycles every minute with distributed ShedLock protection.
     */
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "RecurringBillingJob_executeBillingCycle", lockAtMostFor = "5m", lockAtLeastFor = "10s")
    public void executeBillingCycle() {
        mandateService.executeDueMandates();
    }

    /**
     * Dispatches pre-debit notifications for mandates due within 24 to 48 hours.
     */
    @Scheduled(fixedRate = 300000)
    @SchedulerLock(name = "RecurringBillingJob_dispatchPreDebitNotifications", lockAtMostFor = "5m", lockAtLeastFor = "0s")
    public void dispatchPreDebitNotifications() {
        mandateService.dispatchPreDebitNotifications();
    }
}
