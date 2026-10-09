package com.equinox.core.job;

import com.equinox.core.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReconciliationJob {

    private final ReconciliationService reconciliationService;

    /**
     * Executes daily bank reconciliation batch runner with ShedLock protection.
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @SchedulerLock(name = "DailyReconciliationJob_runDailyReconciliation", lockAtMostFor = "30m", lockAtLeastFor = "0s")
    public void runDailyReconciliation() {
        log.info("Executing daily bank CSV reconciliation check...");
        reconciliationService.verifySystemLedgerDebitCreditInvariant();
    }
}
