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

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.schedule.api.RunLog;
import com.xp.starsite.schedule.api.RunStatus;
import com.xp.starsite.schedule.clock.TimeSource;
import com.xp.starsite.schedule.store.RunLogStore;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 恰一执行器（决策+执行+观测三层的交汇点）：一次触发的完整生命周期。
 *
 * <pre>
 * guard=true:  tryLock(sch:名) ─未获锁─► SKIPPED_LOCKED 流水，返回 false（正常让位非错误）
 *                 │获锁
 *                 ▼
 *             RUNNING 流水 ─► 任务体 ─► DONE / FAILED（异常吞噬留痕）──► unlock（仅持锁者生效）
 * guard=false: 直接 RUNNING ─► 执行（本地模式，每实例都跑）
 * </pre>
 *
 * <p>fail-open 继承 lock 组件 DB 形态语义：tryLock 抛存储异常时<b>放行执行</b>
 * （可用性优先）——极端下可能双跑，因此契约要求任务体幂等（与 async-command
 * 调度锁同款处置）。unlock 异常吞掉（TTL 过期自愈兜底）。
 *
 * <p>本类不做同任务并发去重——那是引擎的 per-instance inFlight 职责；
 * 跨实例互斥由锁保证，锁组件的可重入语义在同一实例内被 inFlight 前置拦截。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class ExactlyOnceRunner {

    private static final Logger log = LoggerFactory.getLogger(ExactlyOnceRunner.class);

    /** 分布式锁（决策层；null = 无锁组件场景——guard 任务注册时已拒绝） */
    private final LockClient lockClient;

    /** 执行流水存储（观测层） */
    private final RunLogStore store;

    /** 时间源（时长计算） */
    private final TimeSource time;

    /** 本实例标识（hostname:pid，与 invest Python holder 同语义） */
    private final String host;

    /** 失败摘要截断上限（error 列 VARCHAR(500)，对齐 async-command last_error 口径） */
    private static final int ERROR_MAX = 500;

    /**
     * 构造。
     *
     * @param lockClient 分布式锁（决策层；可为 null——仅 guard=false 的本地任务可用）
     * @param store      执行流水存储
     * @param time       时间源
     */
    public ExactlyOnceRunner(LockClient lockClient, RunLogStore store, TimeSource time) {
        this.lockClient = lockClient;
        this.store = store;
        this.time = time;
        this.host = resolveHost();
    }

    /**
     * 执行一次触发（同步；引擎在线程池内调用）。
     *
     * @param def         任务定义
     * @param triggerTime 计划触发时刻（misfire 补跑时为错过的计划时刻——留痕诚实）
     * @return true 本实例执行了（DONE 或 FAILED）；false 未获锁让位（SKIPPED_LOCKED）
     */
    public boolean run(ScheduledTaskDef def, LocalDateTime triggerTime) {
        if (def.isGuard() && !acquire(def)) {
            store.skip(def.getName(), def.getLockName(), triggerTime, time.now(), host);
            log.debug("[starsite-schedule] {} 未获锁让位（他方实例执行中）", def.getName());
            return false;
        }
        LocalDateTime startedAt = time.now();
        long startedNanos = System.nanoTime();
        RunLog running = store.start(def.getName(), def.getLockName(), triggerTime, startedAt, host);
        try {
            def.getBody().run();
            store.finish(running.getId(), RunStatus.DONE, elapsedMs(startedNanos), null);
        } catch (Throwable t) {
            // 兜底一切异常（含 Error）：调度体系必须活着，失败留痕到流水
            store.finish(running.getId(), RunStatus.FAILED, elapsedMs(startedNanos), summarize(t));
            log.warn("[starsite-schedule] {} 执行失败: {}", def.getName(), t.toString());
        } finally {
            if (def.isGuard()) {
                releaseQuietly(def);
            }
        }
        return true;
    }

    /**
     * 获取本实例标识。
     *
     * @return hostname:pid
     */
    public String getHost() {
        return host;
    }

    /** 争锁（fail-open：存储异常放行——契约见类 doc） */
    private boolean acquire(ScheduledTaskDef def) {
        try {
            return lockClient.tryLock(def.getLockName());
        } catch (Exception e) {
            log.warn("[starsite-schedule] {} 调度锁异常（fail-open 放行本轮，任务体须幂等）: {}",
                    def.getName(), e.getMessage());
            return true;
        }
    }

    /** 释放（异常吞掉——TTL 过期自愈兜底；非持有者释放无副作用） */
    private void releaseQuietly(ScheduledTaskDef def) {
        try {
            lockClient.unlock(def.getLockName());
        } catch (Exception e) {
            log.warn("[starsite-schedule] {} 释放锁异常（等 TTL 过期自愈）: {}",
                    def.getName(), e.getMessage());
        }
    }

    /** 失败摘要：类型+消息，截断 500 */
    private static String summarize(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        return msg.length() > ERROR_MAX ? msg.substring(0, ERROR_MAX) : msg;
    }

    /** nanoTime 差 → 毫秒（单调钟，不受系统改时影响） */
    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** hostname:pid（hostname 取不到退 localhost——标识仅用于观测留痕） */
    private static String resolveHost() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "localhost";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        return hostname + ":" + (at > 0 ? pid.substring(0, at) : pid);
    }
}
