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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务注册表：任务名 → 定义。重复名快速失败（同名任务在不同实例重复注册是装配错误，
 * 装配期暴露而非静默覆盖）。线程安全（starter 的 BeanPostProcessor 单线程注册，
 * 引擎调度线程只读；仍按并发容器防御——注册与引擎启动在 starter 是两个阶段）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class ScheduleTaskRegistry {

    /** 注册表（注册序遍历——tasks() 输出稳定可读） */
    private final Map<String, ScheduledTaskDef> tasks = new LinkedHashMap<>();

    /**
     * 注册任务（幂等去重场景由调用方决策；同名再次注册抛异常）。
     *
     * @param def 任务定义
     * @throws IllegalArgumentException 同名任务已注册
     */
    public synchronized void register(ScheduledTaskDef def) {
        String name = def.getName();
        ScheduledTaskDef existed = tasks.putIfAbsent(name, def);
        if (existed != null) {
            throw new IllegalArgumentException("同名任务已注册: " + name
                    + "（已有触发器 " + existed.getTrigger().describe() + "）");
        }
    }

    /**
     * 查找任务。
     *
     * @param name 任务名
     * @return 任务定义；未注册为 empty
     */
    public synchronized Optional<ScheduledTaskDef> find(String name) {
        return Optional.ofNullable(tasks.get(name));
    }

    /**
     * 全部任务（注册序快照）。
     *
     * @return 不可变列表
     */
    public synchronized List<ScheduledTaskDef> all() {
        return Collections.unmodifiableList(List.copyOf(tasks.values()));
    }

    /**
     * 已注册数量。
     *
     * @return 数量
     */
    public synchronized int size() {
        return tasks.size();
    }
}
