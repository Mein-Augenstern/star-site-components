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
package com.xp.starsite.lock.client;

import java.util.List;

/**
 * 锁运维门面（与 {@link LockClient} 的正式互斥语义<b>完全隔离</b>）：
 * 运维/管控/诊断场景的侧门——强制释放卡死锁、查持有者、列全部持锁。
 * <p>
 * 隔离设计：本接口不在正常业务链路上出现，装配由
 * {@code starsite.lock.admin-enabled=true} 独立开关控制（默认关）；
 * 业务代码只注入 {@link LockClient}，运维工具/管控端点才注入本接口。
 * <p>
 * 典型场景：应用崩溃后 hold_count 被外部改坏、Redis key 丢 TTL、
 * 需要巡检当前哪些锁被谁持有。
 *
 * @author muyi
 * @date 2026/10/30
 */
public interface LockAdminClient {

    /**
     * 强制释放锁（无视持有者与计数）：清空 locked_by/locked_until/hold_count。
     * 危险操作——仅运维确认卡死后使用；正在执行的持锁方不会收到通知，
     * 释放后立即允许新持有者进入。
     *
     * @param lockName 锁名
     * @return true 释放成功（行存在且已清空）；false 行不存在
     */
    boolean forceRelease(String lockName);

    /**
     * 查询锁的当前持有者（诊断用；同 Provisioner.findHolder 但在运维门面暴露）。
     *
     * @param lockName 锁名
     * @return 持有者 ownerId；无人持有或行不存在返回 null
     */
    String findHolder(String lockName);

    /**
     * 列出当前全部持锁（诊断/巡检用）。
     *
     * @return 持锁信息列表（lock_name/ownerId/holdCount/lockedUntil）；空列表=无锁被持有
     */
    List<LockInfo> listHeldLocks();

    /**
     * 锁状态快照（诊断用）。
     *
     * @param lockName 锁名
     * @return 锁信息；行不存在返回 null
     */
    LockInfo getLockInfo(String lockName);

    /** 锁状态快照值对象（运维只读视角） */
    record LockInfo(String lockName, String ownerId, int holdCount,
                    java.time.LocalDateTime lockedUntil, java.time.LocalDateTime gmtModified) {}
}
