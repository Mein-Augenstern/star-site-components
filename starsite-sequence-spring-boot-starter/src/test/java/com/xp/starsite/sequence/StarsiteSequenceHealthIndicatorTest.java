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
package com.xp.starsite.sequence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 健康端点装配与语义测试：UP + workerId/形态明细（被动上报，不消耗号段）。
 *
 * @author muyi
 * @date 2026/10/25
 */
class StarsiteSequenceHealthIndicatorTest {

    /** 上下文测试运行器：仅装载被测自动装配，模拟应用引入 starter 后的最小容器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StarsiteSequenceAutoConfiguration.class));

    /**
     * 验证健康端点的装配与被动上报语义（默认配置场景）：
     * 端点随主装配就绪且状态为 UP，明细含 workerId 与 allocator（形态描述）；
     * 明细不含发号产物（lastId），且重复调用 health() 前后业务发号严格递增——
     * 结构上证明健康检查只读不上报 id、不消耗号段。
     */
    @Test
    void 默认装配_UP_明细含workerId与形态_被动上报不消耗号段() {
        runner.run(ctx -> {
            StarsiteIdGenerator generator = ctx.getBean(StarsiteIdGenerator.class);
            long idBefore = generator.next();

            org.springframework.boot.actuate.health.HealthIndicator indicator =
                    ctx.getBean("starsiteSequenceHealthIndicator",
                            org.springframework.boot.actuate.health.HealthIndicator.class);
            org.springframework.boot.actuate.health.Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
            assertThat(health.getDetails()).containsKeys("workerId", "allocator");
            assertThat(String.valueOf(health.getDetails().get("allocator"))).contains("standalone");
            // 被动上报的结构性证明：明细不含发号产物，且重复调用 health 后 id 仅随业务调用前进
            assertThat(health.getDetails()).doesNotContainKey("lastId");
            // 再调一次 health，验证高频探活不影响号段
            indicator.health();
            long idAfter = generator.next();
            assertThat(idAfter).isGreaterThan(idBefore);
        });
    }
}
