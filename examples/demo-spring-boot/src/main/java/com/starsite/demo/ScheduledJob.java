package com.starsite.demo;

import com.xp.starsite.lock.client.LockClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job protected by distributed lock — only one instance executes per trigger.
 *
 * @author demo
 */
@Component
public class ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJob.class);

    private final LockClient lockClient;

    public ScheduledJob(LockClient lockClient) {
        this.lockClient = lockClient;
    }

    @Scheduled(fixedDelay = 10000)
    public void nightlyCleanup() {
        if (!lockClient.tryLock("demo-cleanup")) {
            return; // Another instance is running this job
        }
        try {
            log.info("Running cleanup job (only one instance at a time)...");
            // Your business logic here
        } finally {
            lockClient.unlock("demo-cleanup");
        }
    }
}
