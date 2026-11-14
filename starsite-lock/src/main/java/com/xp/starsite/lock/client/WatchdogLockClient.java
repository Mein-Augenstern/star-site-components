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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 看门狗锁包装器：自动为获锁者续期（每 TTL/3 一次），长任务不再需要手工多次 tryLock。
 * 续期失败（锁被他方抢占）自动停止并回调 {@link LockLostListener}。
 *
 * <p><b>重入语义（P0 修复）</b>：本地维护重入深度计数器——内层 unlock 仅递减，
 * 深度归零（外层 unlock）才停止看门狗。此前内层 unlock 无条件停狗，外层仍在
 * 临界区但续期已停 → 长任务超 TTL 后锁被抢占，互斥失效（接口 javadoc 的
 * 嵌套示例即触发）。深度计数是本地性能提示：外部直接操作底层客户端绕过本包装
 * 时计数可能漂移，看门狗最多多续或少续一轮，正确性由底层 TTL 最终兜底。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class WatchdogLockClient implements LockClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WatchdogLockClient.class);

    /** 底层锁客户端（被包装） */
    private final LockClient delegate;

    /** 持锁 TTL（毫秒） */
    private final long ttlMs;

    /** 续期间隔（TTL/3，最小 1s 防过于频繁） */
    private final long renewIntervalMs;

    /** 锁丢失回调（可为 null） */
    private final LockLostListener lockLostListener;

    /** 看门狗调度器（单线程守护——续期任务轻量） */
    private final ScheduledThreadPoolExecutor watchdog;

    /** 活跃续期任务（lockName → future；putIfAbsent 原子注册防重复） */
    private final Map<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();

    /**
     * 重入深度计数（lockName → 本包装器视角的持锁层数；归零才停狗——P0 修复核心）。
     * 底层 delegate 有自己的跨实例计数（DB hold_count 列 / Redis hash），本计数仅
     * 控制看门狗生命周期，不参与互斥判定。
     */
    private final Map<String, AtomicInteger> depths = new ConcurrentHashMap<>();

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
     * @param delegate         底层锁客户端
     * @param ttlMs           持锁 TTL
     * @param lockLostListener 锁丢失回调（可为 null）
     */
    public WatchdogLockClient(LockClient delegate, long ttlMs, LockLostListener lockLostListener) {
        this.delegate = delegate;
        this.ttlMs = ttlMs;
        this.renewIntervalMs = Math.max(ttlMs / 3, 1000); // 最小 1s 防过于频繁
        this.lockLostListener = lockLostListener;
        java.util.concurrent.ThreadFactory factory = new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger seq =
                    new java.util.concurrent.atomic.AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "starsite-lock-watchdog-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.watchdog = new ScheduledThreadPoolExecutor(1, factory);
    }

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
            onAcquired(lockName);
        }
        return acquired;
    }

    /**
     * 限时等待争锁（带看门狗）：成功获取后自动注册续期线程。
     *
     * @param lockName 锁名
     * @param waitMillis 最长等待毫秒
     * @return true 获得锁；false 等待窗口内未获得
     */
    @Override
    public boolean tryLock(String lockName, long waitMillis) {
        boolean acquired = delegate.tryLock(lockName, waitMillis);
        if (acquired) {
            onAcquired(lockName);
        }
        return acquired;
    }

    /**
     * 释放一层锁：深度递减，<b>归零才停止看门狗</b>（P0 修复——重入内层 unlock
     * 不停狗，外层仍在临界区期间续期继续）。非持锁者释放无副作用。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        try {
            delegate.unlock(lockName);
        } finally {
            AtomicInteger depth = depths.get(lockName);
            if (depth != null && depth.decrementAndGet() <= 0) {
                depths.remove(lockName, depth); // 条件移除防并发误删
                stopWatchdog(lockName);
            }
        }
    }

    /**
     * 关闭看门狗线程池（应用停机时调用；AutoCloseable 支持 try-with-resources）。
     */
    @Override
    public void close() {
        watchdog.shutdownNow();
        renewals.clear();
        depths.clear();
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
     * 获锁后处理：深度 +1 + 原子注册看门狗（putIfAbsent 防并发首抢双注册——P1 修复）。
     *
     * @param lockName 锁名
     */
    private void onAcquired(String lockName) {
        depths.computeIfAbsent(lockName, k -> new AtomicInteger()).incrementAndGet();
        startWatchdog(lockName);
    }

    /**
     * 为锁启动看门狗续期线程（putIfAbsent 原子注册——并发首抢同键时多余任务当场取消，
     * 不泄漏续期线程导致锁永不释放——P1 修复）。
     *
     * @param lockName 锁名
     */
    private void startWatchdog(String lockName) {
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
                    depths.remove(lockName);
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

        // 原子注册：另一线程先注册则本任务多余 → 当场取消（防续期任务泄漏）
        ScheduledFuture<?> existing = renewals.putIfAbsent(lockName, future);
        if (existing != null) {
            future.cancel(false);
            log.debug("[starsite-lock-watchdog] 并发首抢：看门狗已由他方注册: lock={}", lockName);
            return;
        }
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
