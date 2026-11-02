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
 * 执行流水状态机（schedule_run_log.status，双语言协议枚举——invest Python 侧同规范）。
 *
 * <pre>
 * RUNNING ──► DONE            正常完成
 *    │────► FAILED            任务体抛异常（error 详情列留痕）
 *    │────► STALE             实例崩溃遗留：启动巡检标死（TTL+宽限阈值），不代表任务失败与否
 * SKIPPED_LOCKED              未获锁跳过（他方实例执行中——恰一执行的另一半，可观测）
 * </pre>
 *
 * @author muyi
 * @date 2026/11/02
 */
public enum RunStatus {

    /** 执行中（start 时写入） */
    RUNNING,

    /** 正常完成 */
    DONE,

    /** 任务体抛异常 */
    FAILED,

    /** 未获锁跳过（恰一执行的让位侧留痕） */
    SKIPPED_LOCKED,

    /** 崩溃遗留的悬挂 RUNNING（启动巡检标死；与 FAILED 语义不同——结果未知） */
    STALE
}
