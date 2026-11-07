package com.starsite.demo;

import com.xp.starsite.schedule.api.MisfirePolicy;
import com.xp.starsite.schedule.api.StarsiteScheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scheduled job protected by distributed lock — only one instance executes per trigger,
 * with an execution log (schedule_run_log) you can query afterwards.
 *
 * <p>Migration note: this used to be a hand-written tryLock/unlock boilerplate on top of
 * {@code @Scheduled} (see git history). Now one annotation does it all — locking,
 * run-log, exception capture, and timezone are handled by starsite-schedule.
 *
 * @author demo
 */
@Component
public class ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJob.class);

    /**
     * Demo trigger fires every 10 seconds so you can watch the run log grow.
     * Real jobs usually use a daily cron like "0 0 8 * * ?".
     */
    @StarsiteScheduled(name = "demo-cleanup", cron = "*/10 * * * * ?",
            misfire = MisfirePolicy.SKIP)
    public void cleanup() {
        log.info("Running cleanup job (only one instance across the cluster)...");
        // Your business logic here — must be idempotent (fail-open contract)
    }
}
