package com.starsite.demo;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints demonstrating each component.
 *
 * @author demo
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final StarsiteIdGenerator idGenerator;
    private final AsyncCommandClient asyncClient;
    private final LockClient lockClient;

    public DemoController(StarsiteIdGenerator idGenerator,
                          AsyncCommandClient asyncClient,
                          LockClient lockClient) {
        this.idGenerator = idGenerator;
        this.asyncClient = asyncClient;
        this.lockClient = lockClient;
    }

    /** Generate a snowflake ID */
    @GetMapping("/id")
    public Map<String, Object> generateId() {
        return Map.of(
                "id", idGenerator.next(),
                "idStr", idGenerator.nextStr(),
                "workerId", idGenerator.getWorkerId());
    }

    /** Submit an async task (will be executed by NotificationExecutor) */
    @PostMapping("/notify")
    public Map<String, Object> submitNotification(String userId, String message) {
        var cmd = asyncClient.submit("send-notification", userId,
                "{\"message\":\"" + message + "\"}");
        return Map.of("commandId", cmd.getId(), "status", cmd.getStatus());
    }

    /** Try to acquire a distributed lock (name defaults to "demo-lock") */
    @GetMapping("/lock")
    public Map<String, Object> tryLock(String name) {
        String lockName = (name == null || name.isBlank()) ? "demo-lock" : name;
        boolean acquired = lockClient.tryLock(lockName);
        if (acquired) {
            lockClient.unlock(lockName);
        }
        return Map.of("lockName", lockName, "acquired", acquired);
    }
}
