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
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 看门狗锁包装器：自动为获锁者续期（每 TTL/3 一次），长任务不再需要手工多次 tryLock。
 * 续期失败（锁被他方抢占）自动停止并回调 {@link LockLostListener}。
 *
 * <p><b>生命周期状态机（三代缺陷的终版修复）</b>：每锁名至多一个活跃 {@link WatchState}
 * （续期任务 + 重入深度同代绑定），全部生命周期迁移（注册/深度增减/拆卸）在同一
 * <b>条纹锁</b>内串行——三条历史缺陷由此构造性关闭：
 * <ol>
 *   <li>P0 重入内层 unlock 停狗：深度在状态对象上，归零（外层释放）才拆卸；</li>
 *   <li>P1 并发首抢双注册：注册仅在状态对象创建时发生，而创建在条纹锁内恰一；</li>
 *   <li>三代竞态（2026-11-14 二轮）：续期失败与"断锁瞬间本实例重抢"交错时，旧任务按
 *       <b>状态身份</b>（{@code watches.remove(name, ws)}）拆卸——只拆自己那代，
 *       新代注册（新状态对象）不受打扰，新持有者的看门狗持续续期。</li>
 * </ol>
 *
 * <p><b>残余漂移（文档化容忍）</b>：锁丢失后原持有者补发的多余 unlock 仍可能递减
 * <i>新代</i>的深度计数（同 ownerId 语义下 delegate 层同样合并计数）——计数是本地
 * 性能提示，正确性由底层 TTL 兜底；绕过本包装器直接操作底层客户端同理。
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

    /** 条纹锁（16 路按锁名散列——生命周期迁移串行化；delegate 调用与回调均在锁外） */
    private final ReentrantLock[] stripes = new ReentrantLock[16];

    /**
     * 活跃看护状态（lockName → 当前代）。值对象的 future/depth 仅在对应条纹锁内访问；
     * 换代 = 放入新对象（旧对象自然作废），身份比对（remove(k, v)）即代际校验。
     */
    private final Map<String, WatchState> watches = new ConcurrentHashMap<>();

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
     * 单代看护状态：续期任务 + 重入深度同代绑定——深度归零拆卸任务，任务失败作废深度。
     */
    private static final class WatchState {

        /** 本代续期任务（创建于条纹锁内，拆卸亦然；关闭路径由 shutdownNow 覆盖） */
        ScheduledFuture<?> future;

        /** 本代重入深度（increment/decrement 全在条纹锁内） */
        final AtomicInteger depth = new AtomicInteger();
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
     * @param delegate         底层锁客户端
     * @param ttlMs           持锁 TTL
     * @param lockLostListener 锁丢失回调（可为 null）
     */
    public WatchdogLockClient(LockClient delegate, long ttlMs, LockLostListener lockLostListener) {
        this.delegate = delegate;
        this.ttlMs = ttlMs;
        this.renewIntervalMs = Math.max(ttlMs / 3, 1000); // 最小 1s 防过于频繁
        this.lockLostListener = lockLostListener;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
        java.util.concurrent.ThreadFactory factory = new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
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
     * 非阻塞争锁（带看门狗）：成功获取后在条纹锁内注册/递增深度——首获创建状态对象
     * 并调度续期任务（恰一），重入复用同代状态仅 +1。
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
     * 释放一层锁：深度递减，<b>归零才拆卸看门狗</b>（重入内层 unlock 不停狗，外层仍在
     * 临界区期间续期继续）。非持锁者释放无副作用。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        try {
            delegate.unlock(lockName);
        } finally {
            ReentrantLock stripe = stripeOf(lockName);
            stripe.lock();
            try {
                WatchState ws = watches.get(lockName);
                if (ws != null && ws.depth.decrementAndGet() <= 0) {
                    // 条件移除按对象身份只拆本代——若期间已换代（锁丢失后重抢），新代不受影响
                    watches.remove(lockName, ws);
                    cancelQuietly(ws.future);
                }
            } finally {
                stripe.unlock();
            }
        }
    }

    /**
     * 关闭看门狗线程池（应用停机时调用；AutoCloseable 支持 try-with-resources）。
     */
    @Override
    public void close() {
        watchdog.shutdownNow();
        watches.clear();
    }

    /**
     * 当前看门狗监控的锁数量（诊断用）。
     *
     * @return 活跃续期任务数
     */
    public int activeWatchCount() {
        return watches.size();
    }

    /**
     * 获锁后处理（条纹锁内）：无状态 → 创建本代状态 + 调度续期（恰一）；有 → 深度 +1。
     *
     * @param lockName 锁名
     */
    private void onAcquired(String lockName) {
        ReentrantLock stripe = stripeOf(lockName);
        stripe.lock();
        try {
            WatchState ws = watches.get(lockName);
            if (ws == null) {
                ws = new WatchState();
                watches.put(lockName, ws);
                ws.future = scheduleRenewal(lockName, ws);
                log.debug("[starsite-lock-watchdog] 看门狗启动: lock={}, renewInterval={}ms",
                        lockName, renewIntervalMs);
            }
            ws.depth.incrementAndGet();
        } finally {
            stripe.unlock();
        }
    }

    /**
     * 调度本代续期任务（须在条纹锁内、状态对象已发布后调用一次）。
     * 续期 = 重入加层 + 立即释放层（计数净效果不变，TTL 已刷新）；
     * 失败 = 锁已丢失 → {@link #onRenewLost}（按代际身份拆卸）。
     *
     * @param lockName 锁名
     * @param ws       本代状态（任务闭包持有其身份用于拆卸校验）
     * @return 续期任务句柄
     */
    private ScheduledFuture<?> scheduleRenewal(String lockName, WatchState ws) {
        return watchdog.scheduleAtFixedRate(() -> {
            try {
                if (delegate.tryLock(lockName)) {
                    delegate.unlock(lockName);
                    log.trace("[starsite-lock-watchdog] 续期成功: lock={}", lockName);
                } else {
                    onRenewLost(lockName, ws);
                }
            } catch (RuntimeException e) {
                log.warn("[starsite-lock-watchdog] 续期异常（下轮重试）: lock={}, err={}",
                        lockName, e.getMessage());
                // 异常不停止看门狗——下一轮继续尝试（可能瞬时网络问题）
            }
        }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 续期失败处理：按<b>状态身份</b>拆卸本代（{@code remove(name, ws)} 只在仍是当前代
     * 时生效——若断锁瞬间已被本实例重抢换代，新代看门狗不受打扰）；拆卸成功才回调
     * 监听器（锁外执行——用户回调可能再次争锁，持锁回调会自锁死）。
     *
     * @param lockName 锁名
     * @param ws       续期任务所属代
     */
    private void onRenewLost(String lockName, WatchState ws) {
        log.warn("[starsite-lock-watchdog] 续期失败，锁已丢失: lock={}", lockName);
        boolean wasCurrent;
        ReentrantLock stripe = stripeOf(lockName);
        stripe.lock();
        try {
            wasCurrent = watches.remove(lockName, ws);
            if (wasCurrent) {
                cancelQuietly(ws.future);
            }
        } finally {
            stripe.unlock();
        }
        if (wasCurrent && lockLostListener != null) {
            lockLostListener.onLockLost(lockName);
        }
    }

    /**
     * 按锁名取条纹锁（散列取模——不同锁名可能同条纹，仅串行化微秒级簿记，delegate
     * 调用与用户回调都在锁外）。
     *
     * @param lockName 锁名
     * @return 对应条纹锁
     */
    private ReentrantLock stripeOf(String lockName) {
        return stripes[(lockName.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    /**
     * 安静取消续期任务（不中断正在执行的续期）。
     *
     * @param future 续期任务句柄（可为 null——创建间隙的拆卸）
     */
    private static void cancelQuietly(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
