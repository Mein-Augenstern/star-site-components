package com.xp.starsite.asynccmd;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康端点装配与语义测试：无死信 UP、有死信 DOWN、明细含状态计数。
 *
 * @author muyi
 * @since 2026-10
 */
class AsyncCommandHealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncCommandAutoConfiguration.class))
            .withPropertyValues("starsite.async-command.store=memory");

    @Autowired
    private ApplicationContext ctx;

    @Test
    void 无命令_UP_明细含各状态计数() {
        runner.run(context -> {
            org.springframework.boot.actuate.health.HealthIndicator indicator =
                    context.getBean("asyncCommandHealthIndicator",
                            org.springframework.boot.actuate.health.HealthIndicator.class);
            org.springframework.boot.actuate.health.Health health = indicator.health();
            assertThat(health.getStatus())
                    .isEqualTo(org.springframework.boot.actuate.health.Status.UP);
            assertThat(health.getDetails()).containsKeys("pending", "running", "dead", "backlogDue");
        });
    }

    @Test
    void 存在死信_DOWN() {
        runner.run(context -> {
            AsyncCommandClient client = context.getBean(AsyncCommandClient.class);
            AsyncCommand cmd = client.submit("t", "k", "{}");
            cmd.setStatus(AsyncCommand.DEAD);
            context.getBean("asyncCommandStore", com.xp.starsite.asynccmd.store.AsyncCommandStore.class)
                    .updateState(cmd);

            org.springframework.boot.actuate.health.HealthIndicator indicator =
                    context.getBean("asyncCommandHealthIndicator",
                            org.springframework.boot.actuate.health.HealthIndicator.class);
            assertThat(indicator.health().getStatus())
                    .isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
            assertThat(indicator.health().getDetails()).containsEntry("dead", 1L);
        });
    }
}
