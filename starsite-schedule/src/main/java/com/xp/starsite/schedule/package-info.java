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

/**
 * 定时任务恰一执行组件（core，零 Spring Boot 依赖）。
 *
 * <p>四层模型（docs/schedule.md）：
 * <ul>
 *   <li>触发（trigger/）：cron / dailyAt / fixedDelay 本地计算下一刻，无需中心</li>
 *   <li>决策（core/ExactlyOnceRunner）：复用 lock 组件原子争锁，全集群每触发点恰一执行</li>
 *   <li>执行：任务体异常吞噬（FAILED 留痕不打断调度），本实例单任务不重入</li>
 *   <li>观测（store/）：schedule_run_log 执行流水（RUNNING/DONE/FAILED/SKIPPED_LOCKED/STALE）</li>
 * </ul>
 *
 * <p><b>幂等契约</b>：决策层 fail-open（锁存储异常时放行执行），极端下可能双跑——
 * 任务体必须幂等（与 lock 组件语义一致，写进契约而非承诺不重）。
 *
 * @author muyi
 * @date 2026/11/02
 */
package com.xp.starsite.schedule;
