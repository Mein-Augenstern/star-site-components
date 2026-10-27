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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重入持有计数（横切职责，Db/Redis 锁实现共享）：锁名 → 本实例持有层数。
 * <p>
 * 语义：{@link #acquire} 持有/重入时层数 +1；{@link #release} 递减并返回剩余层数——
 * &gt; 0 表示外层仍在临界区（存储不动），0 表示归零（真正释放），-1 表示本就无计数
 * （防御性多余释放，调用方直释放存储兜底）。
 * <p>
 * 粒度为 ownerId/实例级（与 Redisson 默认一致）；实例崩溃计数随进程消失，分布式兜底仍靠 TTL。
 *
 * @author muyi
 * @date 2026/10/27
 */
public final class ReentrantHoldCounter {

    /** 持有层数表（锁名 → 层数） */
    private final Map<String, AtomicInteger> holds = new ConcurrentHashMap<>();

    /**
     * 登记一层持有（首次获得或重入）。
     *
     * @param lockName 锁名
     * @return 登记后的持有层数
     */
    public int acquire(String lockName) {
        return holds.computeIfAbsent(lockName, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 退出一层持有。
     *
     * @param lockName 锁名
     * @return 剩余层数：&gt;0 外层仍持锁（存储不动）；0 恰好归零（真正释放）；
     *         -1 本就无计数（防御性多余释放）
     */
    public int release(String lockName) {
        AtomicInteger count = holds.get(lockName);
        if (count == null) {
            return -1;
        }
        int remaining = count.decrementAndGet();
        if (remaining <= 0) {
            holds.remove(lockName, count);
        }
        return remaining;
    }

    /**
     * 当前持有层数（诊断用）。
     *
     * @param lockName 锁名
     * @return 持有层数；未持有返回 0
     */
    public int holdCount(String lockName) {
        AtomicInteger count = holds.get(lockName);
        return count == null ? 0 : count.get();
    }
}
