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
package com.xp.starsite.asynccmd.scheduler;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.clock.TimeSource;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.executor.AsyncCommandBatchExecutor;
import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.executor.ExecutionMode;
import com.xp.starsite.asynccmd.executor.ExecutorRegistry;
import com.xp.starsite.asynccmd.executor.PoolIsolatedExecutor;
import com.xp.starsite.asynccmd.executor.ScheduleMode;
import com.xp.starsite.asynccmd.metrics.AsyncCommandMetrics;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import com.xp.starsite.lock.client.LockClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步命令调度器（纯 core，零 Spring 依赖）：单线程轮询认领 + 组件共享线程池，
 * {@link #start()}/{@link #stop()} 手工生命周期；Spring 场景由 starter 的 SmartLifecycle 包装托管。
 * <p>
 * 每轮：回收悬挂 RUNNING → 令牌认领到期 PENDING → 按类型分组路由。
 * 执行模式（{@link ExecutionMode}，执行器声明）：
 * <ul>
 *   <li>SCHEDULED：调度线程内联执行（串行默认）</li>
 *   <li>ASYNC_POOL：转交线程池（{@link PoolIsolatedExecutor} 隔离池优先，否则组件共享池），
 *       慢任务不阻塞调度线程</li>
 *   <li>IMMEDIATE：提交钩子 {@link #onSubmit(AsyncCommand)} 按 id 立即认领直投线程池，
 *       不等轮询；投递前进程死亡由调度兜底</li>
 * </ul>
 * 共享池归本调度器所有（stop 时优雅关闭）；隔离池归应用所有（只使用不关闭）。
 * 优雅停机：停止认领 → 等在途 tick 与池内任务落终态（上限 {@value #STOP_DRAIN_TIMEOUT_MS}ms）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandScheduler {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandScheduler.class);

    /** 轮询间隔下限：防止误配成 0/负值打爆数据库，start() 中以 max 钳位生效 */
    static final long MIN_POLL_INTERVAL_MS = 200;

    /** 优雅停机的排空上限：超时后放弃等待执行中命令落终态，交由重启后的悬挂回收兜底 */
    private static final long STOP_DRAIN_TIMEOUT_MS = 30_000;

    /** 共享池线程编号（线程名可回溯） */
    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /** 命令存储：悬挂回收/令牌认领/状态回写的落地通道 */
    private final AsyncCommandStore store;
    /** 状态回写客户端：成功/重试/死信终态由它落库 */
    private final AsyncCommandClient client;
    /** 调度参数（轮询间隔/认领批量/悬挂阈值/共享池参数） */
    private final AsyncCommandConfig config;
    /** 执行器注册表：cmdType → 执行器路由 */
    private final ExecutorRegistry registry;
    /** 时间源：认领到期判定基准（测试可注入假时钟） */
    private final TimeSource time;
    /** 指标出口：成功计数/执行耗时（未装配监控时为 NOOP，零开销） */
    private final AsyncCommandMetrics metrics;
    /** 单线程轮询执行器：线程名 starsite-async-cmd（认领专用），daemon 不阻拦 JVM 退出 */
    private final ScheduledExecutorService executor;
    /** 组件共享线程池：ASYNC_POOL/IMMEDIATE 且未隔离的执行器使用（本调度器所有，stop 时关闭） */
    private final ThreadPoolExecutor sharedPool;
    /** 池内在途任务计数：停机排空依据（提交前 +1，finally -1） */
    private final AtomicInteger asyncInFlight = new AtomicInteger();

    /** 运行开关：volatile 保证 stop() 线程的写对调度线程立即可见——置 false 后 tick 空转直接返回 */
    private volatile boolean running = true;

    /** 启动标志：volatile 供 isStarted() 无锁读；检查与置位在 start() 锁内完成，防重复注册 */
    private volatile boolean started = false;

    /** tick 在途标志：volatile 供 stop() 线程无锁探测本轮是否仍在执行 */
    private volatile boolean executingTick = false;

    /** 分布式锁门面（LEADER 模式用；null = COMPETING/EXTERNAL 模式不启用锁） */
    private volatile LockClient scheduleLock;

    /** 当前调度模式（volatile——装配层 setScheduleMode 可能晚于 start 调用） */
    private volatile ScheduleMode scheduleMode = ScheduleMode.COMPETING;

    /** leader 是否持有锁（stop 时判断是否要主动 unlock） */
    /**
     * LEADER 模式"本实例当前持锁"标记（Pod 本地簿记，非分布式权威）：
     * 仅用于 stop() 时决定要不要释放调度锁；选主权威在 DB 锁行（TTL 过期自动让位），
     * 且即使簿记失真导致双实例同时 tick，命令级互斥由令牌认领的原子 UPDATE 兜底——
     * 分布式正确性不依赖此标记。
     */
    private volatile boolean holdingLock = false;

    /**
     * 便捷构造（时间源取系统时钟，指标取 NOOP）。
     *
     * @param store    命令存储
     * @param client   状态回写客户端
     * @param config   调度参数（含共享池参数）
     * @param registry 执行器注册表
     */
    public AsyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                 AsyncCommandConfig config, ExecutorRegistry registry) {
        this(store, client, config, registry, TimeSource.system(), AsyncCommandMetrics.NOOP);
    }

    /**
     * 全参构造。
     *
     * @param store    命令存储
     * @param client   状态回写客户端
     * @param config   调度参数（含共享池参数）
     * @param registry 执行器注册表
     * @param time     时间源（测试注入假时钟用）
     * @param metrics  指标出口（无监控传 {@link AsyncCommandMetrics#NOOP}）
     */
    public AsyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                 AsyncCommandConfig config, ExecutorRegistry registry,
                                 TimeSource time, AsyncCommandMetrics metrics) {
        this.store = store;
        this.client = client;
        this.config = config;
        this.registry = registry;
        this.time = time;
        this.metrics = metrics;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starsite-async-cmd");
            t.setDaemon(true);
            return t;
        });
        this.sharedPool = new ThreadPoolExecutor(
                config.getSharedPoolCoreSize(), config.getSharedPoolMaxSize(),
                60, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(config.getSharedPoolQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "starsite-async-executor-" + POOL_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 队列满时由提交线程自己跑：不丢任务（丢=静默违反可靠性），代价是反压到提交方
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 启动轮询（重复调用幂等）。间隔钳位；scheduleWithFixedDelay 轮次不重叠。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        long interval = Math.max(MIN_POLL_INTERVAL_MS, config.getPollIntervalMs());
        if (scheduleMode == ScheduleMode.EXTERNAL) {
            // 外部触发模式：不自跑调度——外部调度器调 triggerTick()（XXL-Job / K8s CronJob / ElasticJob）
            log.info("[starsite-async-command] 调度器启动（EXTERNAL 模式）：等待外部调度器触发 tick，"
                    + "routes={}", registry.types());
        } else {
            executor.scheduleWithFixedDelay(this::safeTick, interval, interval, TimeUnit.MILLISECONDS);
            log.info("[starsite-async-command] 调度器启动（{} 模式）：interval={}ms, batch={}, "
                            + "staleAfter={}s, sharedPool={}/{}, routes={}",
                    scheduleMode, interval, config.getBatchSize(), config.getStaleAfterSeconds(),
                    config.getSharedPoolCoreSize(), config.getSharedPoolMaxSize(), registry.types());
        }
        started = true;
        running = true;
    }

    /**
     * 优雅停止：置 running=false 并中断调度线程，等在途 tick 与池内任务落终态
     * （上限 STOP_DRAIN_TIMEOUT_MS），随后关闭共享池。隔离池归应用所有，不关闭。
     */
    public void stop() {
        running = false;
        executor.shutdownNow();
        sharedPool.shutdown();
        long deadline = System.currentTimeMillis() + STOP_DRAIN_TIMEOUT_MS;
        while ((executingTick || asyncInFlight.get() > 0) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (holdingLock && scheduleLock != null) {
            scheduleLock.unlock("async-cmd-scheduler");
            holdingLock = false;
        }
        started = false;
        log.info("[starsite-async-command] 调度器停止（池内未落终态任务由重启后悬挂回收兜底）");
    }

    /**
     * 是否已启动。
     *
     * @return true 表示 start() 已生效且未被 stop()
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * 提交钩子（client 落库后回调）：IMMEDIATE 模式即时派发——
     * 按 id 原子认领（多实例并发下认领失败静默跳过），到期且认领成功后直投该类型执行池。
     * 非 IMMEDIATE 类型或延迟命令（nextRunAt 在未来）为无操作。
     *
     * @param cmd 刚落库的命令（status=PENDING）
     */
    public void onSubmit(AsyncCommand cmd) {
        if (!running) {
            return;
        }
        AsyncCommandExecutor handler = registry.find(cmd.getCmdType());
        if (handler == null || handler.mode() != ExecutionMode.IMMEDIATE) {
            return;
        }
        AsyncCommand fresh = store.findById(cmd.getId());
        if (fresh == null || fresh.getNextRunAt() == null
                || fresh.getNextRunAt().isAfter(time.now())) {
            // 延迟提交（runAt 在未来）不走即时派发，等调度到期认领
            return;
        }
        String token = UUID.randomUUID().toString();
        if (!store.claimById(cmd.getId(), token)) {
            // 已被调度线程/其他实例认领——不重复执行
            return;
        }
        AsyncCommand claimed = store.findById(cmd.getId());
        if (claimed == null) {
            return;
        }
        dispatchToPool(handler, List.of(claimed));
    }

    /**
     * tick 的异常兜底：scheduleWithFixedDelay 任务抛未捕获异常会被静默取消，调度永久停摆。
     * LEADER 争锁经 {@link #tryScheduleLock()}：锁存储故障降级为竞争模式继续本轮
     * （令牌认领本身行级原子，多实例并发认领安全），绝不让锁故障停摆调度。
     * 包级可见：同包测试确定性驱动 LEADER 门控（外部消费方入口是 start/tick）。
     */
    void safeTick() {
        try {
            if (scheduleMode == ScheduleMode.LEADER && scheduleLock != null) {
                if (!tryScheduleLock()) {
                    // 非 leader 实例：他方持锁中，跳过本轮（TTL 过期后自动接管）
                    return;
                }
            }
            tick();
        } catch (Exception e) {
            // 调度异常不致命（下一轮继续），但必须可见
            log.error("async scheduler tick error: {}", e.getMessage(), e);
        }
    }

    /**
     * 争调度锁（LEADER 模式）。锁存储异常按 fail-open 放行本轮：
     * 宁可多实例竞争认领（认领是行级原子操作，竞争安全）也不停摆调度。
     *
     * @return true = 本实例持锁（或锁故障降级放行）；false = 他方持锁，跳过本轮
     */
    private boolean tryScheduleLock() {
        try {
            boolean locked = scheduleLock.tryLock("async-cmd-scheduler");
            holdingLock = locked;
            return locked;
        } catch (Exception e) {
            log.warn("[starsite-async-command] 调度锁异常（fail-open 降级竞争模式本轮）: {}", e.getMessage());
            holdingLock = false;
            return true;
        }
    }

    /**
     * 执行单轮调度（public 供测试与运维工具确定性驱动）。
     * 固定顺序：回收悬挂 RUNNING → 令牌认领到期 PENDING → 按类型分组路由执行。
     */
    public void tick() {
        if (!running) {
            return;
        }
        executingTick = true;
        try {
            // 先回收悬挂 RUNNING：不先回收，认领配额会被"僵尸"长期挤占
            int reclaimed = store.reclaimStale(config.getStaleAfterSeconds(), config.getBatchSize());
            if (reclaimed > 0) {
                log.warn("starsite-async reclaimed {} stale RUNNING cmds", reclaimed);
            }
            String token = UUID.randomUUID().toString();
            if (store.claim(token, config.getBatchSize(), time.now()) == 0) {
                // 无到期命令，本轮结束
                return;
            }
            dispatch(emptyIfNull(store.findByToken(token)));
        } finally {
            executingTick = false;
        }
    }

    /**
     * 认领结果分发：无执行器逐条失败处理；其余按 cmdType 分组（保序），
     * 按执行器 mode 分流——SCHEDULED 内联，ASYNC_POOL/IMMEDIATE 整组投池
     * （批量组保持单池任务，保批原子语义）。
     *
     * @param claimed 本轮认领到的命令（不为 null）
     */
    private void dispatch(List<AsyncCommand> claimed) {
        Map<String, List<AsyncCommand>> byType = new LinkedHashMap<>();
        for (AsyncCommand cmd : claimed) {
            if (registry.find(cmd.getCmdType()) == null) {
                // 无执行器按失败处理（记 lastError 进重试/死信），绝不无声跳过
                client.markRetryOrFail(cmd, new IllegalStateException("无注册执行器: " + cmd.getCmdType()));
                continue;
            }
            byType.computeIfAbsent(cmd.getCmdType(), k -> new ArrayList<>()).add(cmd);
        }
        for (Map.Entry<String, List<AsyncCommand>> entry : byType.entrySet()) {
            AsyncCommandExecutor handler = registry.find(entry.getKey());
            List<AsyncCommand> group = entry.getValue();
            if (handler.mode() == ExecutionMode.SCHEDULED) {
                runGroup(handler, group);
            } else {
                dispatchToPool(handler, group);
            }
        }
    }

    /**
     * 整组投池执行：隔离池（{@link PoolIsolatedExecutor}）优先，否则组件共享池；
     * 隔离池拒绝（已关闭等）回退共享池，共享池也拒绝按失败重试（不丢命令）。
     *
     * @param handler 目标类型执行器
     * @param group   同类型命令组
     */
    private void dispatchToPool(AsyncCommandExecutor handler, List<AsyncCommand> group) {
        Runnable task = () -> runGroup(handler, group);
        if (handler instanceof PoolIsolatedExecutor isolated && isolated.pool() != null) {
            try {
                asyncInFlight.incrementAndGet();
                isolated.pool().execute(() -> {
                    try {
                        task.run();
                    } finally {
                        asyncInFlight.decrementAndGet();
                    }
                });
                return;
            } catch (RejectedExecutionException e) {
                asyncInFlight.decrementAndGet();
                log.warn("[starsite-async-command] 隔离池拒绝任务，回退共享池: type={}, err={}",
                        handler.type(), e.getMessage());
            }
        }
        try {
            asyncInFlight.incrementAndGet();
            sharedPool.execute(() -> {
                try {
                    task.run();
                } finally {
                    asyncInFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            asyncInFlight.decrementAndGet();
            // 共享池也满且 CallerRuns 不可用（已 shutdown）：整组按失败重试，命令不丢
            for (AsyncCommand cmd : group) {
                client.markRetryOrFail(cmd, e);
            }
        }
    }

    /**
     * 组执行（批量/单条自适应）：批量执行器整组一次调用（批原子语义），
     * 单条执行器逐条；成功落 SUCCESS + 指标，异常走退避重试。计时覆盖整组。
     *
     * @param handler 目标类型执行器
     * @param group   同类型命令组（至少 1 条）
     */
    private void runGroup(AsyncCommandExecutor handler, List<AsyncCommand> group) {
        if (handler instanceof AsyncCommandBatchExecutor batchExecutor) {
            long begin = System.nanoTime();
            try {
                batchExecutor.executeBatch(group);
            } catch (Exception e) {
                for (AsyncCommand cmd : group) {
                    client.markRetryOrFail(cmd, e);
                }
                return;
            }
            for (AsyncCommand cmd : group) {
                client.markSuccess(cmd);
            }
            metrics.recordSuccess(group.get(0).getCmdType(),
                    Duration.ofNanos(System.nanoTime() - begin));
            return;
        }
        for (AsyncCommand cmd : group) {
            long begin = System.nanoTime();
            try {
                handler.execute(cmd);
            } catch (Exception e) {
                client.markRetryOrFail(cmd, e);
                continue;
            }
            client.markSuccess(cmd);
            metrics.recordSuccess(cmd.getCmdType(), Duration.ofNanos(System.nanoTime() - begin));
        }
    }

    /**
     * null 列表归一为空列表（存储实现可能返回 null 的防御）。
     *
     * @param list 可能为 null 的列表
     * @return 原列表；null 时返回不可变空列表
     */
    static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * 设置调度模式（装配层在 start 前调用；volatile 保证运行期切换可见）。
     *
     * @param mode 目标模式（COMPETING / LEADER / EXTERNAL）
     */
    public void setScheduleMode(ScheduleMode mode) {
        this.scheduleMode = mode == null ? ScheduleMode.COMPETING : mode;
    }

    /**
     * 读取当前调度模式。
     *
     * @return 当前生效的调度模式
     */
    public ScheduleMode getScheduleMode() {
        return scheduleMode;
    }

    /**
     * 设置分布式调度锁（LEADER 模式必设；其他模式可空）。
     *
     * @param lock 分布式锁门面（null = 不使用锁，LEADER 模式退化为 COMPETING）
     */
    public void setScheduleLock(LockClient lock) {
        this.scheduleLock = lock;
    }

    /**
     * 读取当前调度锁（装配接线诊断：LEADER 模式下非 null 才真正选主）。
     *
     * @return 当前锁；未设置为 null
     */
    public LockClient getScheduleLock() {
        return scheduleLock;
    }

    /**
     * 外部触发 tick（EXTERNAL 模式的触发入口——XXL-Job handler / K8s CronJob 调本方法
     * 或经管控端点 {@code POST {admin-base-path}/tick}）。
     * 等价于手动执行一轮调度（回收悬挂 + 认领 + 分发执行）。
     *
     * @throws RuntimeException 调度执行异常（不吞——外部调度器据以告警重试）
     */
    public void triggerTick() {
        tick();
    }
}
