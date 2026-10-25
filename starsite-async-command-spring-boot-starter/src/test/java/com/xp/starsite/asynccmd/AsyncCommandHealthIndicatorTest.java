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

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * 健康端点装配与语义测试：无死信 UP、有死信 DOWN、明细含状态计数。
 *
 * @author muyi
 * @date 2026/10/25
 */
class AsyncCommandHealthIndicatorTest {

    /** 上下文测试运行器：固定 memory 存储形态拉起自动装配，逐用例读取健康端点 Bean 断言 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncCommandAutoConfiguration.class))
            .withPropertyValues("starsite.async-command.store=memory");

    /** 注入的应用上下文（ApplicationContextRunner 场景下保留字段以兼容后续用例扩展） */
    @Autowired
    private ApplicationContext ctx;

    /**
     * 用例：无命令时 UP 且明细含各状态计数。
     * <p>验证点：空存储下健康状态为 UP，明细包含 pending/running/dead/backlogDue 四个键。
     */
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

    /**
     * 用例：存在死信时 DOWN 且明细计入 dead。
     * <p>验证点：构造一条命令并直接置为 DEAD 后，健康状态翻转为 DOWN，dead 明细计数为 1。
     */
    @Test
    void 存在死信_DOWN() {
        runner.run(context -> {
            AsyncCommandClient client = context.getBean(AsyncCommandClient.class);
            AsyncCommand cmd = client.submit("t", "k", "{}");
            // 直接把命令置为 DEAD 模拟重试耗尽的死信，再回写存储
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
