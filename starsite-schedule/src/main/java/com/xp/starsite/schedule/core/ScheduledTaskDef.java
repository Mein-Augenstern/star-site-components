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
package com.xp.starsite.schedule.core;

import com.xp.starsite.schedule.api.MisfirePolicy;
import com.xp.starsite.schedule.api.StarsiteScheduled;
import com.xp.starsite.schedule.trigger.Trigger;
import java.util.Objects;

/**
 * 已注册任务的完整定义（注解的运行时形态；编程式注册直接构造本对象）。
 *
 * <p>不可变——注册后引擎只读；运行态（下次触发/执行中）在引擎侧的 TaskState 里。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class ScheduledTaskDef {

    /** 任务名（全应用唯一） */
    private final String name;

    /** 锁名（sch: 前缀——跨语言协议锚点） */
    private final String lockName;

    /** 触发器 */
    private final Trigger trigger;

    /** 错过策略 */
    private final MisfirePolicy misfire;

    /** 恰一执行开关（false=本地模式每实例都跑） */
    private final boolean guard;

    /** 任务体（引擎线程池内调用；抛出的异常被吞并落 FAILED 流水） */
    private final Runnable body;

    /**
     * 构造。
     *
     * @param name    任务名
     * @param trigger 触发器
     * @param misfire 错过策略
     * @param guard   恰一开关
     * @param body    任务体
     */
    public ScheduledTaskDef(String name, Trigger trigger, MisfirePolicy misfire,
                            boolean guard, Runnable body) {
        this.name = Objects.requireNonNull(name, "任务名不可为空").trim();
        if (this.name.isEmpty()) {
            throw new IllegalArgumentException("任务名不可为空白");
        }
        this.trigger = Objects.requireNonNull(trigger, "触发器不可为空");
        this.misfire = Objects.requireNonNull(misfire, "错过策略不可为空");
        this.body = Objects.requireNonNull(body, "任务体不可为空");
        this.guard = guard;
        this.lockName = StarsiteScheduled.LOCK_PREFIX + this.name;
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
     * @return sch:&lt;任务名&gt;
     */
    public String getLockName() {
        return lockName;
    }

    /**
     * 获取触发器。
     *
     * @return 触发器
     */
    public Trigger getTrigger() {
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
    public boolean isGuard() {
        return guard;
    }

    /**
     * 获取任务体。
     *
     * @return 任务体
     */
    public Runnable getBody() {
        return body;
    }
}
