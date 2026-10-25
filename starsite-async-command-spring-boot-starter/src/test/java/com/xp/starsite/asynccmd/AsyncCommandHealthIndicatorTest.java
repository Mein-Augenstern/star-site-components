/*
 * Copyright 2026 muyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xp.starsite.asynccmd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

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
