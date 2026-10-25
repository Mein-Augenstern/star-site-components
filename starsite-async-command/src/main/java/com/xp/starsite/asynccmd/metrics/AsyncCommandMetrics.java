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

import java.time.Duration;

/**
 * 异步命令指标接口（可观测性抽象）：core 零依赖，默认 {@link #NOOP} 无操作；
 * starter 在 classpath 存在 Micrometer 时自动装配 Micrometer 实现（指标名见其注释）。
 * 自定义监控体系（如内部 metrics SDK）可实现本接口经 @ConditionalOnMissingBean 注入。
 * <p>
 * 实现要求：打点方法不得抛异常（监控故障不能影响命令链路），建议内部 try-catch 吞掉。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandMetrics {

    /** 无操作实现：未装配监控时使用，零开销（空方法体，JIT 可内联消除） */
    AsyncCommandMetrics NOOP = new AsyncCommandMetrics() {
        @Override public void recordSubmitted(String cmdType) { } // NOOP
        @Override public void recordSuccess(String cmdType, Duration executionDuration) { } // NOOP
        @Override public void recordRetry(String cmdType) { } // NOOP
        @Override public void recordDead(String cmdType) { } // NOOP
    };

    /**
     * 命令提交计数（submit/submitAt/submitIdempotent 实际落库时调用；
     * submitIdempotent 跳过时不调用——只统计真实新命令）。
     *
     * @param cmdType 命令类型（作为指标 tag）
     */
    void recordSubmitted(String cmdType);

    /**
     * 命令执行成功计数 + 执行耗时分布。
     *
     * @param cmdType           命令类型（作为指标 tag）
     * @param executionDuration 单次（批量为整批）执行耗时，由调度器计时
     */
    void recordSuccess(String cmdType, Duration executionDuration);

    /**
     * 命令进入重试计数（指数退避每次触发时调用；一次命令生命周期可能多次调用）。
     *
     * @param cmdType 命令类型（作为指标 tag）
     */
    void recordRetry(String cmdType);

    /**
     * 命令进入死信计数（重试超限转 DEAD 时调用——最需要告警盯防的信号）。
     *
     * @param cmdType 命令类型（作为指标 tag）
     */
    void recordDead(String cmdType);
}
