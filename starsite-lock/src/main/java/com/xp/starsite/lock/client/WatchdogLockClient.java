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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 看门狗锁装饰器：包装任意 {@link LockClient}，为每个成功获取的锁启动后台续期线程——
 * 在 TTL 到期前自动调用 tryLock 续期（重入计数 +1 后由随后的 unlock -1 抵消），
 * 长任务持锁跨 TTL 场景不再需要手工多次 tryLock。
 * <p>
 * 工作机制：
 * <ul>
 *   <li>tryLock 成功 → 注册该锁到看门狗，每 {@code ttlMs / 3} 毫秒自动续期一次</li>
 *   <li>续期成功 → 继续下一轮；重入计数 +1 与 -1 在同一轮内配对完成</li>
 *   <li>续期失败（网络分区/锁被他方抢占）→ 停止看门狗 + 调用 {@link LockLostListener}（可选）</li>
 *   <li>unlock → 停止看门狗 + 委托底层释放</li>
 * </ul>
 * <p>
 * 使用方式（starter 装配或手工包装）：
 * <pre>{@code
 * LockClient dbLock = new DbLockClient(mapper, provisioner, table, owner, ttl);
 * WatchdogLockClient watchdog = new WatchdogLockClient(dbLock, ttlMs);
 * if (watchdog.tryLock("long-task")) {
 *     try {
 *         // 长时间执行（超过 TTL 也不会丢锁——看门狗在续期）
 *     } finally {
 *         watchdog.unlock("long-task");  // 停止看门狗并释放
 *     }
 * }
 * }</pre>
 * <p>
 * 线程安全：看门狗线程池为单线程 daemon（不阻断 JVM 退出）；注册表线程安全。
 * <p>
 * 已知限制：看门狗线程自身不可用（JVM 关闭/线程池满）时锁会正常走 TTL 过期——
 * 语义退化为无看门狗的普通锁，不会更差。
 *
 * @author muyi
 * @date 2026/10/31
 */
public class WatchdogLockClient implements LockClient, AutoCloseable {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(WatchdogLockClient.class);

    /** 被包装的底层锁客户端 */
    private final LockClient delegate;

    /** 锁 TTL 毫秒（看门狗以 TTL/3 间隔续期） */
    private final long ttlMs;

    /** 续期间隔（TTL/3，保证 TTL 到期前至少两次续期机会） */
    private final long renewIntervalMs;

    /** 看门狗调度线程（daemon 单线程——不阻断 JVM 退出） */
    private final ScheduledExecutorService watchdog;

    /** 活跃续期任务表（锁名 → 定时任务句柄） */
    private final Map<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();

    /** 锁丢失监听器（可选——续期失败时回调，业务侧可记录/告警/中断长任务） */
    private final LockLostListener lockLostListener;

    /**
     * 锁丢失监听器：看门狗续期失败（锁已被他方抢占或存储不可达超过一个 TTL）时回调。
     */
    @FunctionalInterface
    public interface LockLostListener {

        /**
         * 锁丢失回调（看门狗已停止，锁将按 TTL 自然过期）。
         *
         * @param lockName 锁名
         */
        void onLockLost(String lockName);
    }

    /**
     * 构造（无锁丢失监听）。
     *
     * @param delegate 底层锁客户端
     * @param ttlMs    锁 TTL 毫秒（看门狗以 TTL/3 间隔续期）
     */
    public WatchdogLockClient(LockClient delegate, long ttlMs) {
        this(delegate, ttlMs, null);
    }

    /**
     * 全参构造（带锁丢失监听）。
     *
     * @param delegate          底层锁客户端
     * @param ttlMs             锁 TTL 毫秒
     * @param lockLostListener  锁丢失回调（null = 只记日志）
     */
    public WatchdogLockClient(LockClient delegate, long ttlMs, LockLostListener lockLostListener) {
        this.delegate = delegate;
        this.ttlMs = ttlMs;
        this.renewIntervalMs = Math.max(ttlMs / 3, 1000); // 最小 1s 防过于频繁
        this.lockLostListener = lockLostListener;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starsite-lock-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 非阻塞争锁（带看门狗）：成功获取后自动注册续期线程。
     * 后续每次自动续期 = delegate.tryLock（重入 +1）+ delegate.unlock（-1），
     * 净效果 = TTL 刷新 + 计数不变。
     *
     * @param lockName 锁名
     * @return true 获得锁（看门狗已启动）；false 他方持锁中
     */
    @Override
    public boolean tryLock(String lockName) {
        boolean acquired = delegate.tryLock(lockName);
        if (acquired) {
            startWatchdog(lockName);
        }
        return acquired;
    }

    /**
     * 限时等待争锁（带看门狗）：成功获取后自动注册续期线程。
     *
     * @param lockName  锁名
     * @param waitMillis 最长等待毫秒
     * @return true 获得锁；false 等待窗口内未获得
     */
    @Override
    public boolean tryLock(String lockName, long waitMillis) {
        boolean acquired = delegate.tryLock(lockName, waitMillis);
        if (acquired) {
            startWatchdog(lockName);
        }
        return acquired;
    }

    /**
     * 释放锁：先停止看门狗（防止释放后续期复活锁），再委托底层释放。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        stopWatchdog(lockName);
        delegate.unlock(lockName);
    }

    /**
     * 关闭看门狗线程池（应用停机时调用；AutoCloseable 支持 try-with-resources）。
     */
    @Override
    public void close() {
        watchdog.shutdownNow();
        renewals.clear();
    }

    /**
     * 当前看门狗监控的锁数量（诊断用）。
     *
     * @return 活跃续期任务数
     */
    public int activeWatchCount() {
        return renewals.size();
    }

    /**
     * 为锁启动看门狗续期线程。
     *
     * @param lockName 锁名
     */
    private void startWatchdog(String lockName) {
        // 已有看门狗（重入）→ 不重复注册（同一锁名只有一个续期线程）
        if (renewals.containsKey(lockName)) {
            return;
        }
        ScheduledFuture<?> future = watchdog.scheduleAtFixedRate(() -> {
            try {
                // 续期 = 重入加层 + 立即释放层（计数净效果 = 不变，TTL 已刷新）
                if (delegate.tryLock(lockName)) {
                    delegate.unlock(lockName);
                    log.trace("[starsite-lock-watchdog] 续期成功: lock={}", lockName);
                } else {
                    // 续期失败 = 锁已丢失（他方抢占或存储异常超过 fail-open 窗口）
                    log.warn("[starsite-lock-watchdog] 续期失败，锁已丢失: lock={}", lockName);
                    stopWatchdog(lockName);
                    if (lockLostListener != null) {
                        lockLostListener.onLockLost(lockName);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[starsite-lock-watchdog] 续期异常（下轮重试）: lock={}, err={}",
                        lockName, e.getMessage());
                // 异常不停止看门狗——下一轮继续尝试（可能瞬时网络问题）
            }
        }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
        renewals.put(lockName, future);
        log.debug("[starsite-lock-watchdog] 看门狗启动: lock={}, renewInterval={}ms", lockName, renewIntervalMs);
    }

    /**
     * 停止指定锁的看门狗。
     *
     * @param lockName 锁名
     */
    private void stopWatchdog(String lockName) {
        ScheduledFuture<?> future = renewals.remove(lockName);
        if (future != null) {
            future.cancel(false); // 不中断正在执行的续期
            log.debug("[starsite-lock-watchdog] 看门狗停止: lock={}", lockName);
        }
    }
}
