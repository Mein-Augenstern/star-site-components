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
package com.xp.starsite.tuner.api;

import java.time.LocalDateTime;

/**
 * 受管池快照（实际参数 + 活跃度 + 目标预案——治理视图的最小单元）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class PoolSnapshot {

    /** 池名 */
    private final String poolName;

    /** 实际核心线程数 */
    private final int coreSize;

    /** 实际最大线程数 */
    private final int maxSize;

    /** 实际保活秒 */
    private final int keepAliveSeconds;

    /** 当前活跃线程数（执行中任务数） */
    private final int activeCount;

    /** 队列积压数 */
    private final int queueDepth;

    /** 当前应生效预案名（无绑定无窗口为 "baseline"） */
    private final String targetPreset;

    /** 最近一次实际变更时刻（从未变更为 null） */
    private final LocalDateTime lastAppliedAt;

    /**
     * 全参构造（引擎装配；Long 形态适配 MyBatis——本快照不落库，统一 long 直填）。
     *
     * @param poolName       池名
     * @param coreSize       核心数
     * @param maxSize        最大数
     * @param keepAliveSeconds 保活秒
     * @param activeCount    活跃数
     * @param queueDepth     队列积压
     * @param targetPreset   目标预案名
     * @param lastAppliedAt  最近变更时刻
     */
    public PoolSnapshot(String poolName, int coreSize, int maxSize, int keepAliveSeconds,
                        int activeCount, int queueDepth, String targetPreset,
                        LocalDateTime lastAppliedAt) {
        this.poolName = poolName;
        this.coreSize = coreSize;
        this.maxSize = maxSize;
        this.keepAliveSeconds = keepAliveSeconds;
        this.activeCount = activeCount;
        this.queueDepth = queueDepth;
        this.targetPreset = targetPreset;
        this.lastAppliedAt = lastAppliedAt;
    }

    /**
     * 获取池名。
     *
     * @return 池名
     */
    public String getPoolName() {
        return poolName;
    }

    /**
     * 获取实际核心线程数。
     *
     * @return 核心数
     */
    public int getCoreSize() {
        return coreSize;
    }

    /**
     * 获取实际最大线程数。
     *
     * @return 最大数
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 获取实际保活秒。
     *
     * @return 保活秒
     */
    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    /**
     * 获取活跃线程数。
     *
     * @return 活跃数
     */
    public int getActiveCount() {
        return activeCount;
    }

    /**
     * 获取队列积压数。
     *
     * @return 积压数
     */
    public int getQueueDepth() {
        return queueDepth;
    }

    /**
     * 获取当前应生效预案名。
     *
     * @return 预案名（无治理配置为 baseline）
     */
    public String getTargetPreset() {
        return targetPreset;
    }

    /**
     * 获取最近一次实际变更时刻。
     *
     * @return 变更时刻；从未变更为 null
     */
    public LocalDateTime getLastAppliedAt() {
        return lastAppliedAt;
    }
}
