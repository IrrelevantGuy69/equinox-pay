package com.equinox.core.job;

import com.equinox.core.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyPayoutBatchJob {

    private final SettlementService settlementService;

    /**
     * Executes daily payout batch runner with ShedLock protection.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    @SchedulerLock(name = "DailyPayoutBatchJob_runDailyPayoutBatches", lockAtMostFor = "15m", lockAtLeastFor = "0s")
    public void runDailyPayoutBatches() {
        settlementService.processDailyPayoutBatches();
    }
}
