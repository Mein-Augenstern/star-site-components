package com.starsite.demo;

import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Async task executor: simulates sending a notification.
 * Normal return = success; exception = retry with exponential backoff.
 *
 * @author demo
 */
@Component
public class NotificationExecutor implements AsyncCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(NotificationExecutor.class);

    @Override
    public String type() {
        return "send-notification";
    }

    @Override
    public void execute(AsyncCommand cmd) {
        log.info("Sending notification: bizKey={}, payload={}", cmd.getBizKey(), cmd.getPayload());
        // In real code: call SMS/email/push service here
        // Throw exception to trigger retry: throw new RuntimeException("SMS gateway timeout");
    }
}
