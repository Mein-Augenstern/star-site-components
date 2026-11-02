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

import java.util.List;
import java.util.Optional;

/**
 * 调度门面（业务方编程式入口；注解是声明式入口，两者共用同一引擎与锁协议）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public interface ScheduleClient {

    /**
     * 手动触发一次任务（运维补偿/测试用）。
     * <p>走与定时触发<b>同一把锁</b>——手动与定时不会双跑（invest 手动拉数不参与锁的教训
     * 产品化）；本实例该任务执行中时拒绝（false）。
     *
     * @param name 任务名
     * @return true 已受理（异步执行，结果见流水）；false 任务不存在或本实例正在执行
     */
    boolean triggerNow(String name);

    /**
     * 全部已注册任务快照。
     *
     * @return 任务状态列表（注册序）
     */
    List<TaskStatus> tasks();

    /**
     * 单任务快照。
     *
     * @param name 任务名
     * @return 状态；未注册为 empty
     */
    Optional<TaskStatus> status(String name);
}
