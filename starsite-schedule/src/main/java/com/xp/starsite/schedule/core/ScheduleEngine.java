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

import com.xp.starsite.schedule.api.MisfirePolicy;
import com.xp.starsite.schedule.api.RunLog;
import com.xp.starsite.schedule.api.ScheduleClient;
import com.xp.starsite.schedule.api.TaskStatus;
import com.xp.starsite.schedule.clock.TimeSource;
import com.xp.starsite.schedule.store.RunLogStore;
import com.xp.starsite.schedule.trigger.Trigger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调度引擎（触发层）：单线程 tick 循环扫注册表，到点任务交执行池
 * {@link ExactlyOnceRunner}（形态沿用 async-command 调度器：core 手工 start/stop，
 * starter 的 SmartLifecycle 托管）。
 *
 * <p>每任务运行态（TaskState）：下次触发时刻 + 本实例 inFlight 标记——
 * 同任务在本实例永不并发（防锁组件可重入语义下的同实例双跑）；跨实例互斥由锁保证。
 *
 * <p>启动期两件巡检：
 * <ul>
 *   <li>悬挂回收：上次实例遗留的 RUNNING 超阈值标 STALE（结果未知≠失败）</li>
 *   <li>misfire：RUN_ONCE 策略对比最近 DONE 与时刻表，缺口补跑一次（SKIP 不动）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/11/02
 */
public class ScheduleEngine implements ScheduleClient {

    private static final Logger log = LoggerFactory.getLogger(ScheduleEngine.class);

    /** 默认 tick 间隔（毫秒）：亚秒精度非本组件目标（要秒级平台见 docs/schedule.md §9） */
    public static final long DEFAULT_TICK_MS = 500;

    /** 默认执行池大小 */
    public static final int DEFAULT_POOL_SIZE = 2;

    /** 优雅停机排水上限（毫秒）：等在途任务落终态，超时放弃（RUNNING 行由下次启动巡检标 STALE） */
    public static final long STOP_DRAIN_TIMEOUT_MS = 10_000;

    /** 注册表 */
    private final ScheduleTaskRegistry registry;

    /** 恰一执行器 */
    private final ExactlyOnceRunner runner;

    /** 执行流水存储 */
    private final RunLogStore store;

    /** 时间源 */
    private final TimeSource time;

    /** tick 间隔毫秒 */
    private final long tickMs;

    /** 悬挂 RUNNING 判死阈值（启动巡检用） */
    private final Duration staleThreshold;

    /** 执行池大小 */
    private final int poolSize;

    /** 调度线程（单线程——触发计算轻，串行足够） */
    private ScheduledExecutorService scheduler;

    /** 任务执行池 */
    private ExecutorService workerPool;

    /** tick 任务句柄（stop 取消） */
    private ScheduledFuture<?> tickFuture;

    /** 每任务运行态 */
    private final Map<String, TaskState> states = new ConcurrentHashMap<>();

    /** 引擎启动标记 */
    private volatile boolean started = false;

    /** 任务运行态：下次触发时刻 + 本实例执行中标记 */
    private static final class TaskState {

        /** 下次触发时刻（LocalDateTime——任务时区本地时间；null=尚未初始化） */
        volatile LocalDateTime nextRun;

        /** 本实例执行中标记（inFlight 防同实例并发） */
        volatile boolean inFlight;

        /** 触发时刻语义：true=间隔型（完成后再排），false=时刻表型（按计划时刻推进） */
        final boolean fromCompletion;

        TaskState(boolean fromCompletion) {
            this.fromCompletion = fromCompletion;
        }
    }

    /**
     * 便捷构造（系统时钟、默认 tick/池/悬挂阈值）。
     *
     * @param registry 注册表
     * @param runner   恰一执行器
     * @param store    流水存储
     */
    public ScheduleEngine(ScheduleTaskRegistry registry, ExactlyOnceRunner runner, RunLogStore store) {
        this(registry, runner, store, TimeSource.system(), DEFAULT_TICK_MS,
                DEFAULT_POOL_SIZE, Duration.ofHours(1));
    }

    /**
     * 全参构造。
     *
     * @param registry       注册表
     * @param runner         恰一执行器
     * @param store          流水存储
     * @param time           时间源（测试注入假时钟）
     * @param tickMs         tick 间隔毫秒
     * @param poolSize       执行池大小
     * @param staleThreshold 悬挂 RUNNING 判死阈值
     */
    public ScheduleEngine(ScheduleTaskRegistry registry, ExactlyOnceRunner runner, RunLogStore store,
                          TimeSource time, long tickMs, int poolSize, Duration staleThreshold) {
        this.registry = registry;
        this.runner = runner;
        this.store = store;
        this.time = time;
        this.tickMs = tickMs;
        this.staleThreshold = staleThreshold;
        this.poolSize = poolSize;
    }

    /**
     * 启动引擎：巡检（悬挂回收 + misfire）→ 初始化各任务下次触发 → 起 tick 循环。
     * <p>幂等：重复启动忽略（记 warn）。
     */
    public synchronized void start() {
        if (started) {
            log.warn("[starsite-schedule] 引擎已启动，忽略重复 start()");
            return;
        }
        int stale = store.markStaleRunning(time.now().minus(staleThreshold));
        if (stale > 0) {
            log.info("[starsite-schedule] 启动巡检：{} 条悬挂 RUNNING 标 STALE（阈值 {}）",
                    stale, staleThreshold);
        }
        // 线程池先行创建（misfire 补跑在任务初始化时即提交执行）
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starsite-schedule-tick");
            t.setDaemon(true);
            return t;
        });
        workerPool = Executors.newFixedThreadPool(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "starsite-schedule-worker");
            t.setDaemon(true);
            return t;
        });
        LocalDateTime now = time.now();
        for (ScheduledTaskDef def : registry.all()) {
            TaskState state = stateOf(def);
            state.nextRun = firstNextRun(def, now);
            if (def.getMisfire() == MisfirePolicy.RUN_ONCE) {
                LocalDateTime missed = firstMissedTriggerTime(def, now);
                if (missed != null) {
                    log.info("[starsite-schedule] {} misfire 补跑（错过计划时刻 {}）", def.getName(), missed);
                    submit(def, missed, state);
                }
            }
        }
        tickFuture = scheduler.scheduleWithFixedDelay(this::tick, tickMs, tickMs, TimeUnit.MILLISECONDS);
        started = true;
        log.info("[starsite-schedule] 引擎启动：任务 {} 个，tick {}ms，悬挂阈值 {}",
                registry.size(), tickMs, staleThreshold);
    }

    /**
     * 停止引擎：取消 tick → 关执行池 → 等在途落终态（上限 STOP_DRAIN_TIMEOUT_MS）。
     * 未落终态的 RUNNING 行由下次启动巡检标 STALE（崩溃语义与宕机一致，不撒谎）。
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (tickFuture != null) {
            tickFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(STOP_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    log.warn("[starsite-schedule] 停机排水超时（在途任务 RUNNING 行将由下次启动标 STALE）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("[starsite-schedule] 引擎停止");
    }

    /**
     * 是否已启动。
     *
     * @return true=start() 生效中
     */
    public boolean isStarted() {
        return started;
    }

    /** {@inheritDoc} */
    @Override
    public boolean triggerNow(String name) {
        if (!started) {
            return false;
        }
        ScheduledTaskDef def = registry.find(name).orElse(null);
        if (def == null) {
            return false;
        }
        TaskState state = stateOf(def);
        if (state.inFlight) {
            return false;
        }
        submit(def, time.now(), state);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskStatus> tasks() {
        List<TaskStatus> list = new ArrayList<>();
        for (ScheduledTaskDef def : registry.all()) {
            list.add(snapshot(def));
        }
        return list;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TaskStatus> status(String name) {
        return registry.find(name).map(this::snapshot);
    }

    /** 单任务快照（最近流水来自共享存储——全集群视角） */
    private TaskStatus snapshot(ScheduledTaskDef def) {
        TaskState state = states.get(def.getName());
        RunLog last = store.recentByTask(def.getName(), 1).stream().findFirst().orElse(null);
        return new TaskStatus(def.getName(), def.getLockName(), def.getTrigger().describe(),
                def.getMisfire(), def.isGuard(),
                state == null ? null : state.nextRun,
                state != null && state.inFlight, last);
    }

    /** tick：扫全部任务，到点且空闲则提交执行（调度线程内，只做轻计算） */
    private void tick() {
        try {
            LocalDateTime now = time.now();
            for (ScheduledTaskDef def : registry.all()) {
                TaskState state = stateOf(def);
                if (state.inFlight || state.nextRun == null || now.isBefore(state.nextRun)) {
                    continue;
                }
                LocalDateTime planned = state.nextRun;
                // 时刻表型：按计划时刻推进（执行延迟不漂移）；间隔型：完成后由执行回调再排
                if (!state.fromCompletion) {
                    state.nextRun = def.getTrigger().nextAfter(planned);
                }
                submit(def, planned, state);
            }
        } catch (Exception e) {
            // tick 异常不能杀死循环（scheduleWithFixedDelay 遇异常会停——必须全兜）
            log.warn("[starsite-schedule] tick 异常（已吞，循环继续）: {}", e.toString());
        }
    }

    /** 提交执行（inFlight 置位；完成回调里间隔型任务再排下一轮） */
    private void submit(ScheduledTaskDef def, LocalDateTime triggerTime, TaskState state) {
        state.inFlight = true;
        try {
            workerPool.execute(() -> {
                try {
                    runner.run(def, triggerTime);
                } finally {
                    if (state.fromCompletion) {
                        state.nextRun = def.getTrigger().nextAfter(time.now());
                    }
                    state.inFlight = false;
                }
            });
        } catch (RejectedExecutionException e) {
            // 池已关（停机中）：复位标记，流水不撒谎
            state.inFlight = false;
            log.warn("[starsite-schedule] {} 提交被拒（引擎停机中）", def.getName());
        }
    }

    /** 任务运行态（懒建） */
    private TaskState stateOf(ScheduledTaskDef def) {
        return states.computeIfAbsent(def.getName(),
                k -> new TaskState(def.getTrigger().rescheduleFromCompletion()));
    }

    /** 启动期首个下次触发时刻：时刻表型=now 之后首刻；间隔型=now+间隔 */
    private LocalDateTime firstNextRun(ScheduledTaskDef def, LocalDateTime now) {
        if (def.getTrigger().rescheduleFromCompletion()) {
            return def.getTrigger().nextAfter(now);
        }
        return def.getTrigger().nextAfter(now.minusNanos(1));
    }

    /**
     * misfire 判定：最近 DONE 的触发时刻之后的<b>首个</b>计划时刻若已过去且未被执行 → 缺口。
     * <p>从未成功过的任务<b>不回溯</b>（无法界定历史起点，且 cron 攀爬有 4 年上界）——
     * 首次运行从下一个计划时刻起算；间隔型无"计划时刻"概念，错过策略不适用。
     */
    private LocalDateTime firstMissedTriggerTime(ScheduledTaskDef def, LocalDateTime now) {
        Trigger trigger = def.getTrigger();
        if (trigger.rescheduleFromCompletion()) {
            return null;
        }
        Optional<LocalDateTime> lastDone = store.lastDoneTriggerTime(def.getName());
        if (lastDone.isEmpty()) {
            return null;
        }
        LocalDateTime candidate = trigger.nextAfter(lastDone.get());
        return candidate.isBefore(now) ? candidate : null;
    }
}
