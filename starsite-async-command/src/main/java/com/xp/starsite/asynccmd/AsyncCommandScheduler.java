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
package com.xp.starsite.asynccmd;

import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步命令调度器（纯 core，零 Spring 依赖）：自持单线程 ScheduledExecutorService，
 * {@link #start()}/{@link #stop()} 手工生命周期；Spring 场景由 starter 的 SmartLifecycle 包装托管。
 * <p>
 * 每轮：回收悬挂 RUNNING（执行进程死亡残留）→ 令牌认领到期 PENDING →
 * {@link ExecutorRegistry} 按类型路由执行 → 成功落 SUCCESS / 失败指数退避回 PENDING / 超限进 DEAD。
 * 多实例部署时各实例独立令牌认领，同一命令只会被一个实例执行（谁写入算谁）。
 * 优雅停机：停止认领新命令，等待本轮执行中的命令落终态后退出（超时由重启后的悬挂回收兜底）。
 * <p>
 * 线程安全性：start() 加锁幂等；stop()/tick() 可与内部调度线程并发调用，
 * 跨线程状态可见性由 volatile 标志保证（见字段注释）；单线程 executor 保证轮次串行不重叠。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandScheduler {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandScheduler.class);

    /** 轮询间隔下限：防止误配成 0/负值打爆数据库（一轮 DB 访问本身也要毫秒级），start() 中以 max 钳位生效 */
    static final long MIN_POLL_INTERVAL_MS = 200;

    /** 优雅停机的排空上限：超时后放弃等待执行中命令落终态，交由重启后的悬挂回收兜底 */
    private static final long STOP_DRAIN_TIMEOUT_MS = 30_000;

    /** 命令存储：悬挂回收/令牌认领/状态回写的落地通道 */
    private final AsyncCommandStore store;
    /** 状态回写客户端：成功/重试/死信终态由它落库 */
    private final AsyncCommandClient client;
    /** 调度参数（轮询间隔/认领批量/悬挂阈值） */
    private final AsyncCommandConfig config;
    /** 执行器注册表：cmdType → 执行器路由 */
    private final ExecutorRegistry registry;
    /** 时间源：认领到期判定基准（测试可注入假时钟） */
    private final TimeSource time;
    /** 单线程调度执行器：线程名 starsite-async-cmd（出错可回溯），daemon 不阻拦 JVM 退出 */
    private final ScheduledExecutorService executor;

    /** 运行开关：volatile 保证 stop() 线程的写对调度线程立即可见——置 false 后 tick 空转直接返回 */
    private volatile boolean running = true;

    /** 启动标志：volatile 供 isStarted() 无锁读到最新值；检查与置位在 start() 锁内完成，防重复注册调度任务 */
    private volatile boolean started = false;

    /** tick 在途标志：volatile 供 stop() 线程无锁探测本轮是否仍在执行，作为优雅排空等待的依据 */
    private volatile boolean executingTick = false;

    /**
     * 便捷构造（时间源取系统时钟）。
     *
     * @param store    命令存储
     * @param client   状态回写客户端
     * @param config   调度参数
     * @param registry 执行器注册表
     */
    public AsyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                 AsyncCommandConfig config, ExecutorRegistry registry) {
        this(store, client, config, registry, TimeSource.system());
    }

    /**
     * 全参构造。
     *
     * @param store    命令存储
     * @param client   状态回写客户端
     * @param config   调度参数
     * @param registry 执行器注册表
     * @param time     时间源（测试注入假时钟用）
     */
    public AsyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                 AsyncCommandConfig config, ExecutorRegistry registry, TimeSource time) {
        this.store = store;
        this.client = client;
        this.config = config;
        this.registry = registry;
        this.time = time;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starsite-async-cmd");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动轮询（重复调用幂等，二次调用为无操作）。
     * synchronized 保证并发 start() 下"检查 started—注册任务—置位"原子，不会重复注册调度任务；
     * 间隔取 max(MIN_POLL_INTERVAL_MS, 配置值) 钳位；scheduleWithFixedDelay 以上一轮跑完起算间隔，轮次天然不重叠。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        long interval = Math.max(MIN_POLL_INTERVAL_MS, config.getPollIntervalMs());
        executor.scheduleWithFixedDelay(this::safeTick, interval, interval, TimeUnit.MILLISECONDS);
        started = true;
        running = true;
        log.info("[starsite-async-command] 调度器启动：interval={}ms, batch={}, staleAfter={}s, routes={}",
                interval, config.getBatchSize(), config.getStaleAfterSeconds(), registry.types());
    }

    /**
     * 优雅停止：置 running=false（后续 tick 静默空转）并中断内部调度线程，
     * 随后以 100ms 步进等待在途 tick 落终态，最多等 STOP_DRAIN_TIMEOUT_MS 毫秒；
     * 超时未落终态的命令不阻塞退出，交由重启后的悬挂回收兜底。
     * 等待期间被中断则恢复中断标志并立即结束（不吞中断）。
     */
    public void stop() {
        running = false;
        executor.shutdownNow();
        long deadline = System.currentTimeMillis() + STOP_DRAIN_TIMEOUT_MS;
        while (executingTick && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        started = false;
        log.info("[starsite-async-command] 调度器停止");
    }

    /**
     * 是否已启动。
     *
     * @return true 表示 start() 已生效且未被 stop()；volatile 读，无需加锁
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * tick 的异常兜底：捕获一切异常记 error 日志后正常返回。
     * 为什么必须兜底：scheduleWithFixedDelay 的任务一旦抛出未捕获异常会被静默取消，调度将永久停摆。
     */
    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            // 调度异常不致命（下一轮继续），但必须可见
            log.error("async scheduler tick error: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行单轮调度（public 供测试与运维工具确定性驱动，不依赖内部调度线程）。
     * 固定顺序：回收悬挂 RUNNING → 令牌认领到期 PENDING → 逐条路由执行并回写终态/重试。
     * 认领 0 条时本轮直接结束；执行期间 executingTick 为 true，供 stop() 优雅排空等待。
     */
    public void tick() {
        if (!running) {
            return;
        }
        executingTick = true;
        try {
            // 先回收悬挂 RUNNING（执行进程死亡残留）：不先回收，认领配额会被"僵尸"长期挤占
            int reclaimed = store.reclaimStale(config.getStaleAfterSeconds(), config.getBatchSize());
            if (reclaimed > 0) {
                log.warn("starsite-async reclaimed {} stale RUNNING cmds", reclaimed);
            }
            // 本轮认领令牌：随机 UUID，多实例互斥的关键（谁写入算谁）
            String token = UUID.randomUUID().toString();
            if (store.claim(token, config.getBatchSize(), time.now()) == 0) {
                // 无到期命令，本轮结束
                return;
            }
            for (AsyncCommand cmd : emptyIfNull(store.findByToken(token))) {
                AsyncCommandExecutor handler = registry.find(cmd.getCmdType());
                if (handler == null) {
                    // 无执行器按失败处理（记 lastError 进重试/死信），绝不无声跳过——命令不能丢
                    client.markRetryOrFail(cmd, new IllegalStateException("无注册执行器: " + cmd.getCmdType()));
                    continue;
                }
                try {
                    handler.execute(cmd);
                    client.markSuccess(cmd);
                } catch (Exception e) {
                    // 执行器抛异常 = 命令失败，交由退避重试
                    client.markRetryOrFail(cmd, e);
                }
            }
        } finally {
            executingTick = false;
        }
    }

    /**
     * null 列表归一为空列表（存储实现可能返回 null 的防御）。
     *
     * @param list 可能为 null 的列表
     * @return 原列表；入参为 null 时返回不可变空列表
     */
    static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
