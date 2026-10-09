package com.equinox.core.job;

import com.equinox.core.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClearUnclearedFundsJob {

    private final SettlementService settlementService;

    /**
     * Executes T+1 clearing sweeper every minute with ShedLock protection.
     * Clears captured funds older than 24 hours.
     */
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "ClearUnclearedFundsJob_clearUnclearedFunds", lockAtMostFor = "5m", lockAtLeastFor = "0s")
    public void clearUnclearedFunds() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        settlementService.clearUnclearedFunds(cutoff);
    }
}
