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
import java.util.Optional;

/**
 * 任务运行时快照（ScheduleClient.tasks()/status() 的返回行）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class TaskStatus {

    /** 任务名 */
    private final String name;

    /** 锁名（sch: 前缀） */
    private final String lockName;

    /** 触发器描述（如 "cron 0 0 8 * * ? @Asia/Shanghai" / "dailyAt 20:00" / "fixedDelay 60s"） */
    private final String trigger;

    /** 错过策略 */
    private final MisfirePolicy misfire;

    /** 恰一执行开关（false=本地模式每实例都跑） */
    private final boolean guarded;

    /** 下次触发时刻（任务时区本地时间；null=引擎未启动） */
    private final LocalDateTime nextRun;

    /** 是否正在执行（本实例视角） */
    private final boolean running;

    /** 最近一次执行流水（全集群可见——来自共享存储） */
    private final RunLog lastRun;

    /**
     * 全参构造。
     *
     * @param name      任务名
     * @param lockName  锁名
     * @param trigger   触发器描述
     * @param misfire   错过策略
     * @param guarded   恰一开关
     * @param nextRun   下次触发时刻（可 null）
     * @param running   本实例执行中标记
     * @param lastRun   最近流水（可 null）
     */
    public TaskStatus(String name, String lockName, String trigger, MisfirePolicy misfire,
                      boolean guarded, LocalDateTime nextRun, boolean running, RunLog lastRun) {
        this.name = name;
        this.lockName = lockName;
        this.trigger = trigger;
        this.misfire = misfire;
        this.guarded = guarded;
        this.nextRun = nextRun;
        this.running = running;
        this.lastRun = lastRun;
    }

    /**
     * 获取任务名。
     *
     * @return 任务名
     */
    public String getName() {
        return name;
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
     * 获取触发器描述。
     *
     * @return 触发器描述
     */
    public String getTrigger() {
        return trigger;
    }

    /**
     * 获取错过策略。
     *
     * @return 错过策略
     */
    public MisfirePolicy getMisfire() {
        return misfire;
    }

    /**
     * 是否启用跨实例恰一。
     *
     * @return true=恰一模式
     */
    public boolean isGuarded() {
        return guarded;
    }

    /**
     * 获取下次触发时刻。
     *
     * @return 下次触发时刻；引擎未启动为 empty
     */
    public Optional<LocalDateTime> getNextRun() {
        return Optional.ofNullable(nextRun);
    }

    /**
     * 本实例是否正在执行该任务。
     *
     * @return true=执行中
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取最近一次执行流水。
     *
     * @return 最近流水；从未执行为 empty
     */
    public Optional<RunLog> getLastRun() {
        return Optional.ofNullable(lastRun);
    }
}
