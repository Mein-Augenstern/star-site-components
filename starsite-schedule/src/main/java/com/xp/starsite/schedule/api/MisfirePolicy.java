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

/**
 * 错过策略：全部实例停机跨越触发点后的处置（单实例宕机不属于"错过"——
 * 其他实例照常触发执行，恰一性由锁保证）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public enum MisfirePolicy {

    /** 错过就错过，等下一个触发点（默认——幂等数据任务最安全，不追补） */
    SKIP,

    /**
     * 启动时补跑一次：对比流水最近 DONE 的触发时刻与时刻表推算，中间有缺口则
     * 立即执行一次（trigger_time 记缺口里的首个错过的计划时刻）。只补一次，不回放。
     */
    RUN_ONCE
}
