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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Micrometer 指标实现（starter 装配，classpath 存在 micrometer 时生效）。
 * <p>
 * 指标清单：
 * <ul>
 *   <li>Counter {@code starsite.async.commands}{@code status=submitted|success|retry|dead, cmdType=…}
 *       —— 命令生命周期四态计数（retry 是"进入重试次数"，一次命令可能多次）</li>
 *   <li>Timer {@code starsite.async.execution}{@code cmdType=…}
 *       —— 执行耗时分布（批量执行器记整批一次；P95/P99 看这里）</li>
 * </ul>
 * 告警建议：dead 计数 &gt; 0 即需人工介入（与健康端点"死信即 DOWN"同一语义）；
 * retry 速率突增通常预示下游故障。
 * <p>
 * 实现约束：所有打点方法内部兜底不抛异常（监控故障不能影响命令链路）；
 * registry 由容器注入（Boot 的 SimpleMeterRegistry→Prometheus 等自动适配）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class MicrometerAsyncCommandMetrics implements AsyncCommandMetrics {

    /** 指标命名空间前缀（全部指标以它开头，便于在 metrics 端点按前缀过滤） */
    static final String METRIC_COMMANDS = "starsite.async.commands";

    /** 执行耗时指标名（Timer） */
    static final String METRIC_EXECUTION = "starsite.async.execution";

    /** 命令类型 tag 名（取执行器 type()） */
    static final String TAG_CMD_TYPE = "cmdType";

    /** 生命周期状态 tag 名（submitted/success/retry/dead） */
    static final String TAG_STATUS = "status";

    /** Micrometer 注册表（构造注入，线程安全） */
    private final MeterRegistry registry;

    /**
     * 构造。
     *
     * @param registry Micrometer 注册表（Boot 自动配置的 MeterRegistry Bean）
     */
    public MicrometerAsyncCommandMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@inheritDoc} 计数 starsite.async.commands{status=submitted,cmdType} */
    @Override
    public void recordSubmitted(String cmdType) {
        try {
            registry.counter(METRIC_COMMANDS, TAG_STATUS, "submitted", TAG_CMD_TYPE, cmdType).increment();
        } catch (RuntimeException ignored) {
            // 监控打点失败不影响命令链路
        }
    }

    /** {@inheritDoc} 计数 + 耗时记录（Timer starsite.async.execution{cmdType}） */
    @Override
    public void recordSuccess(String cmdType, Duration executionDuration) {
        try {
            registry.counter(METRIC_COMMANDS, TAG_STATUS, "success", TAG_CMD_TYPE, cmdType).increment();
            Timer.builder(METRIC_EXECUTION)
                    .description("异步命令执行耗时（批量执行器为整批耗时）")
                    .tag(TAG_CMD_TYPE, cmdType)
                    .register(registry)
                    .record(executionDuration);
        } catch (RuntimeException ignored) {
            // 监控打点失败不影响命令链路
        }
    }

    /** {@inheritDoc} 计数 starsite.async.commands{status=retry,cmdType} */
    @Override
    public void recordRetry(String cmdType) {
        try {
            registry.counter(METRIC_COMMANDS, TAG_STATUS, "retry", TAG_CMD_TYPE, cmdType).increment();
        } catch (RuntimeException ignored) {
            // 监控打点失败不影响命令链路
        }
    }

    /** {@inheritDoc} 计数 starsite.async.commands{status=dead,cmdType}——最应告警盯防的信号 */
    @Override
    public void recordDead(String cmdType) {
        try {
            registry.counter(METRIC_COMMANDS, TAG_STATUS, "dead", TAG_CMD_TYPE, cmdType).increment();
        } catch (RuntimeException ignored) {
            // 监控打点失败不影响命令链路
        }
    }
}
