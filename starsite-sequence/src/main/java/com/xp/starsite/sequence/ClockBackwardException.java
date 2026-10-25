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

/**
 * 时钟回拨超限异常：回拨幅度超过 {@code maxBackwardMillis} 且策略为 FAIL 时抛出。
 * 抛出即拒绝发号——宁可短暂不可用，绝不发出时间戳倒退的 id。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class ClockBackwardException extends RuntimeException {

    /** 观测到的回拨毫秒数（正数，= 上次发号毫秒 - 当前毫秒） */
    private final long backwardMillis;

    /** 配置容忍上限毫秒数 */
    private final long maxBackwardMillis;

    /**
     * 以回拨幅度与容忍上限构造异常，message 已携带两个数值便于日志直读。
     *
     * @param backwardMillis    观测到的回拨毫秒数（正数）
     * @param maxBackwardMillis 配置的容忍上限毫秒数
     */
    public ClockBackwardException(long backwardMillis, long maxBackwardMillis) {
        super("时钟回拨 %d ms 超过容忍上限 %d ms，按策略拒绝发号".formatted(backwardMillis, maxBackwardMillis));
        this.backwardMillis = backwardMillis;
        this.maxBackwardMillis = maxBackwardMillis;
    }

    /**
     * 获取观测到的回拨毫秒数。
     *
     * @return 回拨毫秒数（正数）
     */
    public long getBackwardMillis() {
        return backwardMillis;
    }

    /**
     * 获取配置的回拨容忍上限。
     *
     * @return 容忍上限毫秒数
     */
    public long getMaxBackwardMillis() {
        return maxBackwardMillis;
    }
}
