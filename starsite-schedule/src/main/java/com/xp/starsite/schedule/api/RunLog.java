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
package com.xp.starsite.schedule.api;

import java.time.LocalDateTime;

/**
 * 一条执行流水（schedule_run_log 行；列表查询不含 error 大列——列表/详情分列铁律）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class RunLog {

    /** 主键 id（sequence 组件发号，禁 AUTO_INCREMENT——组件表规范 D2） */
    private final long id;

    /** 任务名 */
    private final String taskName;

    /** 锁名（sch:&lt;任务名&gt;；跨语言协议锚点） */
    private final String lockName;

    /** 本次计划触发时刻（misfire 判定基准：与实际 started_at 的差 = 触发延迟） */
    private final LocalDateTime triggerTime;

    /** 实际开始时刻 */
    private final LocalDateTime startedAt;

    /** 终态时刻（进行中/跳过为 null） */
    private final LocalDateTime finishedAt;

    /** 执行实例（hostname:pid——invest Python holder 同语义） */
    private final String host;

    /** 终态/进行中状态 */
    private final RunStatus status;

    /** 执行时长毫秒（终态才有值） */
    private final Long durationMs;

    /** 失败原因摘要（仅 FAILED 详情行携带；列表查询不返回） */
    private final String error;

    /**
     * 列表形态构造（无 error——列表/详情分列铁律 D6；MyBatis 列表映射走本构造）。
     *
     * @param id          主键（MyBatis 反射按包装类型找构造器——Long 形态适配，null 防）
     * @param taskName    任务名
     * @param lockName    锁名
     * @param triggerTime 计划触发时刻
     * @param startedAt   实际开始时刻
     * @param finishedAt  终态时刻（可 null）
     * @param host        执行实例
     * @param status      状态
     * @param durationMs  时长毫秒（可 null）
     */
    public RunLog(Long id, String taskName, String lockName, LocalDateTime triggerTime,
                  LocalDateTime startedAt, LocalDateTime finishedAt, String host,
                  RunStatus status, Long durationMs) {
        this(id == null ? 0L : id, taskName, lockName, triggerTime, startedAt, finishedAt,
                host, status, durationMs, null);
    }

    /**
     * 全参构造（存储层装配与 MyBatis 构造器映射共用——统一 Long 形态：
     * MyBatis 反射按包装类型找构造器，业务侧 long 实参自动装箱，无歧义）。
     *
     * @param id          主键（可 null——反射映射缺列时视为 0）
     * @param taskName    任务名
     * @param lockName    锁名
     * @param triggerTime 计划触发时刻
     * @param startedAt   实际开始时刻
     * @param finishedAt  终态时刻（可 null）
     * @param host        执行实例
     * @param status      状态
     * @param durationMs  时长毫秒（可 null）
     * @param error       失败摘要（可 null）
     */
    public RunLog(Long id, String taskName, String lockName, LocalDateTime triggerTime,
                  LocalDateTime startedAt, LocalDateTime finishedAt, String host,
                  RunStatus status, Long durationMs, String error) {
        this.id = id == null ? 0L : id;
        this.taskName = taskName;
        this.lockName = lockName;
        this.triggerTime = triggerTime;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.host = host;
        this.status = status;
        this.durationMs = durationMs;
        this.error = error;
    }

    /**
     * 获取主键。
     *
     * @return 主键 id
     */
    public long getId() {
        return id;
    }

    /**
     * 获取任务名。
     *
     * @return 任务名
     */
    public String getTaskName() {
        return taskName;
    }

    /**
     * 获取锁名。
     *
     * @return 锁名
     */
    public String getLockName() {
        return lockName;
    }

    /**
     * 获取计划触发时刻。
     *
     * @return 计划触发时刻
     */
    public LocalDateTime getTriggerTime() {
        return triggerTime;
    }

    /**
     * 获取实际开始时刻。
     *
     * @return 实际开始时刻
     */
    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    /**
     * 获取终态时刻。
     *
     * @return 终态时刻；进行中/跳过为 null
     */
    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    /**
     * 获取执行实例标识。
     *
     * @return hostname:pid
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取状态。
     *
     * @return 状态枚举
     */
    public RunStatus getStatus() {
        return status;
    }

    /**
     * 获取执行时长。
     *
     * @return 时长毫秒；进行中/跳过为 null
     */
    public Long getDurationMs() {
        return durationMs;
    }

    /**
     * 获取失败摘要。
     *
     * @return 失败摘要；非 FAILED 或列表查询为 null
     */
    public String getError() {
        return error;
    }
}
