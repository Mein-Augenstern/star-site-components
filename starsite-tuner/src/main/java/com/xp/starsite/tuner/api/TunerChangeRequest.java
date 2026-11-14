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
 * 待审批的参数变更请求（审批钩子 SPI 的入参——变更前回调，DENY 即跳过应用）。
 *
 * @param poolName   池名
 * @param presetName 目标预案名
 * @param source     触发源（SCHEDULE/MANUAL/ADAPTIVE/STARTUP——审批是否放行窗口切换由业务定）
 * @param oldCore    旧核心数
 * @param oldMax     旧最大数
 * @param oldKeep    旧保活秒
 * @param oldQueue   旧队列容量（null=不涉及）
 * @param newCore    新核心数
 * @param newMax     新最大数
 * @param newKeep    新保活秒
 * @param newQueue   新队列容量（null=不涉及）
 * @param host       发起实例
 * @param occurredAt 请求时刻
 * @author muyi
 * @date 2026/11/14
 */
public final class TunerChangeRequest {

    /** 池名 */
    private final String poolName;

    /** 目标预案名 */
    private final String presetName;

    /** 触发源 */
    private final TunerAuditEntry.Source source;

    /** 旧核心数 */
    private final int oldCore;

    /** 旧最大数 */
    private final int oldMax;

    /** 旧保活秒 */
    private final int oldKeep;

    /** 旧队列容量（null=不涉及） */
    private final Integer oldQueue;

    /** 新核心数 */
    private final int newCore;

    /** 新最大数 */
    private final int newMax;

    /** 新保活秒 */
    private final int newKeep;

    /** 新队列容量（null=不涉及） */
    private final Integer newQueue;

    /** 发起实例 */
    private final String host;

    /** 请求时刻 */
    private final LocalDateTime occurredAt;

    /**
     * 全参构造。
     *
     * @param poolName   池名
     * @param presetName 目标预案名
     * @param source     触发源
     * @param oldCore    旧核心数
     * @param oldMax     旧最大数
     * @param oldKeep    旧保活秒
     * @param oldQueue   旧队列容量（null=不涉及）
     * @param newCore    新核心数
     * @param newMax     新最大数
     * @param newKeep    新保活秒
     * @param newQueue   新队列容量（null=不涉及）
     * @param host       发起实例
     * @param occurredAt 请求时刻
     */
    public TunerChangeRequest(String poolName, String presetName,
                              TunerAuditEntry.Source source,
                              int oldCore, int oldMax, int oldKeep, Integer oldQueue,
                              int newCore, int newMax, int newKeep, Integer newQueue,
                              String host, LocalDateTime occurredAt) {
        this.poolName = poolName;
        this.presetName = presetName;
        this.source = source;
        this.oldCore = oldCore;
        this.oldMax = oldMax;
        this.oldKeep = oldKeep;
        this.oldQueue = oldQueue;
        this.newCore = newCore;
        this.newMax = newMax;
        this.newKeep = newKeep;
        this.newQueue = newQueue;
        this.host = host;
        this.occurredAt = occurredAt;
    }

    /**
     * 获取池名。
     *
     * @return 池名
     */
    public String poolName() {
        return poolName;
    }

    /**
     * 获取目标预案名。
     *
     * @return 预案名
     */
    public String presetName() {
        return presetName;
    }

    /**
     * 获取触发源。
     *
     * @return 触发源
     */
    public TunerAuditEntry.Source source() {
        return source;
    }

    /**
     * 获取旧核心数。
     *
     * @return 旧核心数
     */
    public int oldCore() {
        return oldCore;
    }

    /**
     * 获取旧最大数。
     *
     * @return 旧最大数
     */
    public int oldMax() {
        return oldMax;
    }

    /**
     * 获取旧保活秒。
     *
     * @return 旧保活秒
     */
    public int oldKeep() {
        return oldKeep;
    }

    /**
     * 获取旧队列容量。
     *
     * @return 旧队列容量；null=不涉及
     */
    public Integer oldQueue() {
        return oldQueue;
    }

    /**
     * 获取新核心数。
     *
     * @return 新核心数
     */
    public int newCore() {
        return newCore;
    }

    /**
     * 获取新最大数。
     *
     * @return 新最大数
     */
    public int newMax() {
        return newMax;
    }

    /**
     * 获取新保活秒。
     *
     * @return 新保活秒
     */
    public int newKeep() {
        return newKeep;
    }

    /**
     * 获取新队列容量。
     *
     * @return 新队列容量；null=不涉及
     */
    public Integer newQueue() {
        return newQueue;
    }

    /**
     * 获取发起实例。
     *
     * @return 实例标识
     */
    public String host() {
        return host;
    }

    /**
     * 获取请求时刻。
     *
     * @return 请求时刻
     */
    public LocalDateTime occurredAt() {
        return occurredAt;
    }
}
