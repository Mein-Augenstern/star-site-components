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
 * 变更审计条目（tuner_audit 行）：一次<b>实际生效</b>的参数变更（old→new 值差为审计
 * 的最小单元；no-op 应用不记——审计只留真变更）。per-host 留痕：10 实例集群切换预案
 * = 10 行，每行是那台 JVM 的真实账面变化——合规审计要的就是真实性而非聚合。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerAuditEntry {

    /** 触发源：SCHEDULE（窗口切换）/ MANUAL（admin 手动 reload）/ STARTUP（启动基线对齐） */
    public enum Source {
        /** 窗口/绑定经轮询自动生效 */
        SCHEDULE,
        /** admin 手动触发 reload */
        MANUAL,
        /** 启动时对齐当前应生效参数 */
        STARTUP
    }

    /** 审计 id */
    private final long id;

    /** 变更时刻 */
    private final LocalDateTime occurredAt;

    /** 本实例标识（hostname:pid） */
    private final String host;

    /** 池名 */
    private final String poolName;

    /** 生效预案名 */
    private final String presetName;

    /** 触发源 */
    private final Source source;

    /** 旧核心线程数（变更前与变更后相同则该字段与新值一致） */
    private final int oldCore;

    /** 旧最大线程数 */
    private final int oldMax;

    /** 旧保活秒 */
    private final int oldKeep;

    /** 新核心线程数 */
    private final int newCore;

    /** 新最大线程数 */
    private final int newMax;

    /** 新保活秒 */
    private final int newKeep;

    /**
     * 全参构造（引擎写入与存储装配共用；Long 形态适配 MyBatis）。
     *
     * @param id         审计 id
     * @param occurredAt 变更时刻
     * @param host       实例标识
     * @param poolName   池名
     * @param presetName 预案名
     * @param source     触发源
     * @param oldCore    旧核心数
     * @param oldMax     旧最大数
     * @param oldKeep    旧保活秒
     * @param newCore    新核心数
     * @param newMax     新最大数
     * @param newKeep    新保活秒
     */
    public TunerAuditEntry(Long id, LocalDateTime occurredAt, String host, String poolName,
                           String presetName, Source source,
                           Integer oldCore, Integer oldMax, Integer oldKeep,
                           Integer newCore, Integer newMax, Integer newKeep) {
        this.id = id == null ? 0L : id;
        this.occurredAt = occurredAt;
        this.host = host;
        this.poolName = poolName;
        this.presetName = presetName;
        this.source = source;
        this.oldCore = oldCore == null ? 0 : oldCore;
        this.oldMax = oldMax == null ? 0 : oldMax;
        this.oldKeep = oldKeep == null ? 0 : oldKeep;
        this.newCore = newCore == null ? 0 : newCore;
        this.newMax = newMax == null ? 0 : newMax;
        this.newKeep = newKeep == null ? 0 : newKeep;
    }

    /**
     * 获取审计 id。
     *
     * @return 审计 id
     */
    public long getId() {
        return id;
    }

    /**
     * 获取变更时刻。
     *
     * @return 变更时刻
     */
    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    /**
     * 获取实例标识。
     *
     * @return hostname:pid
     */
    public String getHost() {
        return host;
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
     * 获取预案名。
     *
     * @return 预案名
     */
    public String getPresetName() {
        return presetName;
    }

    /**
     * 获取触发源。
     *
     * @return 触发源
     */
    public Source getSource() {
        return source;
    }

    /**
     * 获取旧核心线程数。
     *
     * @return 旧核心数
     */
    public int getOldCore() {
        return oldCore;
    }

    /**
     * 获取旧最大线程数。
     *
     * @return 旧最大数
     */
    public int getOldMax() {
        return oldMax;
    }

    /**
     * 获取旧保活秒。
     *
     * @return 旧保活秒
     */
    public int getOldKeep() {
        return oldKeep;
    }

    /**
     * 获取新核心线程数。
     *
     * @return 新核心数
     */
    public int getNewCore() {
        return newCore;
    }

    /**
     * 获取新最大线程数。
     *
     * @return 新最大数
     */
    public int getNewMax() {
        return newMax;
    }

    /**
     * 获取新保活秒。
     *
     * @return 新保活秒
     */
    public int getNewKeep() {
        return newKeep;
    }
}
