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
package com.xp.starsite.asynccmd.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.AsyncCommandMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Micrometer 指标实现测试：四态计数与执行耗时 Timer 在 SimpleMeterRegistry 上的正确性。
 *
 * @author muyi
 * @date 2026/10/25
 */
class MicrometerAsyncCommandMetricsTest {

    /** 直读计数（counter 无 tag 时唯一；这里按 name+tags 精确取） */
    private static double counter(MeterRegistry registry, String status, String cmdType) {
        return registry.get(MicrometerAsyncCommandMetrics.METRIC_COMMANDS)
                .tag("status", status).tag("cmdType", cmdType)
                .counter().count();
    }

    @Test
    void 四态计数_按状态与类型正确累加() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AsyncCommandMetrics metrics = new MicrometerAsyncCommandMetrics(registry);

        metrics.recordSubmitted("push");
        metrics.recordSubmitted("push");
        metrics.recordSubmitted("audit");
        metrics.recordSuccess("push", Duration.ofMillis(5));
        metrics.recordRetry("push");
        metrics.recordDead("audit");

        assertThat(counter(registry, "submitted", "push")).isEqualTo(2.0);
        assertThat(counter(registry, "submitted", "audit")).isEqualTo(1.0);
        assertThat(counter(registry, "success", "push")).isEqualTo(1.0);
        assertThat(counter(registry, "retry", "push")).isEqualTo(1.0);
        assertThat(counter(registry, "dead", "audit")).isEqualTo(1.0);
        // 类型隔离：push 的 dead 计数不受 audit 影响
        assertThat(registry.find(MicrometerAsyncCommandMetrics.METRIC_COMMANDS)
                .tag("status", "dead").tag("cmdType", "push").counter()).isNull();
    }

    @Test
    void 执行耗时_记录进Timer且可统计次数() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AsyncCommandMetrics metrics = new MicrometerAsyncCommandMetrics(registry);

        metrics.recordSuccess("push", Duration.ofMillis(120));
        metrics.recordSuccess("push", Duration.ofMillis(280));

        io.micrometer.core.instrument.Timer timer = registry
                .get(MicrometerAsyncCommandMetrics.METRIC_EXECUTION)
                .tag("cmdType", "push").timer();
        assertThat(timer.count()).isEqualTo(2);
        // 累计耗时 = 120 + 280 = 400ms（纳秒级断言容差）
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(399.0, 401.0);
    }

    @Test
    void 打点内部异常不外抛_命令链路不受监控故障影响() {
        // closed registry 模拟注册表故障（已关闭后 counter() 抛 IllegalStateException）；
        // 实现约定吞掉打点异常——本用例固化该契约
        MeterRegistry registry = new SimpleMeterRegistry();
        AsyncCommandMetrics metrics = new MicrometerAsyncCommandMetrics(registry);
        registry.close();

        metrics.recordSubmitted("push"); // 不抛
        metrics.recordSuccess("push", Duration.ofMillis(1)); // 不抛
        metrics.recordRetry("push"); // 不抛
        metrics.recordDead("push"); // 不抛
        // SimpleMeterRegistry.close 后计数行为不保证，这里只验证"不抛异常"契约本身
    }

    @Test
    void NOOP实现_全方法零副作用不抛异常() {
        AsyncCommandMetrics.NOOP.recordSubmitted("any");
        AsyncCommandMetrics.NOOP.recordSuccess("any", Duration.ZERO);
        AsyncCommandMetrics.NOOP.recordRetry("any");
        AsyncCommandMetrics.NOOP.recordDead("any");
        // 到这里没抛异常即通过
    }
}
