package com.xp.starsite.asynccmd;

import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 异步命令调度器（纯 core，零 Spring 依赖）：自持单线程 ScheduledExecutorService，
 * {@link #start()}/{@link #stop()} 手工生命周期；Spring 场景由 starter 的 SmartLifecycle 包装托管。
 * <p>
 * 每轮：回收悬挂 RUNNING（执行进程死亡残留）→ 令牌认领到期 PENDING →
 * {@link ExecutorRegistry} 按类型路由执行 → 成功落 SUCCESS / 失败指数退避回 PENDING / 超限进 DEAD。
 * 多实例部署时各实例独立令牌认领，同一命令只会被一个实例执行（谁写入算谁）。
 * 优雅停机：停止认领新命令，等待本轮执行中的命令落终态后退出（超时由重启后的悬挂回收兜底）。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncCommandScheduler.class);

    private final AsyncCommandStore store;
    private final AsyncCommandClient client;
    private final AsyncCommandConfig config;
    private final ExecutorRegistry registry;
    private final ScheduledExecutorService executor;

    private volatile boolean running = true;
    private volatile boolean started = false;
    private volatile boolean executingTick = false;

    public AsyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                 AsyncCommandConfig config, ExecutorRegistry registry) {
        this.store = store;
        this.client = client;
        this.config = config;
        this.registry = registry;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starsite-async-cmd");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动轮询（重复调用幂等） */
    public synchronized void start() {
        if (started) {
            return;
        }
        long interval = Math.max(200, config.getPollIntervalMs());
        executor.scheduleWithFixedDelay(this::safeTick, interval, interval, TimeUnit.MILLISECONDS);
        started = true;
        running = true;
        log.info("[starsite-async-command] 调度器启动：interval={}ms, batch={}, staleAfter={}s, routes={}",
                interval, config.getBatchSize(), config.getStaleAfterSeconds(), registry.types());
    }

    /** 优雅停止：不再认领新命令，等待执行中命令落终态（最多 30s，超时由重启后悬挂回收兜底） */
    public void stop() {
        running = false;
        executor.shutdownNow();
        long deadline = System.currentTimeMillis() + 30_000;
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

    public boolean isStarted() {
        return started;
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            // 调度异常不致命（下一轮继续），但必须可见
            log.error("async scheduler tick error: {}", e.getMessage(), e);
        }
    }

    /** 单轮调度（public 供测试与运维工具确定性驱动） */
    public void tick() {
        if (!running) {
            return;
        }
        executingTick = true;
        try {
            int reclaimed = store.reclaimStale(config.getStaleAfterSeconds(), config.getBatchSize());
            if (reclaimed > 0) {
                log.warn("starsite-async reclaimed {} stale RUNNING cmds", reclaimed);
            }
            String token = UUID.randomUUID().toString();
            if (store.claim(token, config.getBatchSize(), LocalDateTime.now()) == 0) {
                return;
            }
            for (AsyncCommand cmd : emptyIfNull(store.findByToken(token))) {
                AsyncCommandExecutor handler = registry.find(cmd.getCmdType());
                if (handler == null) {
                    client.markRetryOrFail(cmd, new IllegalStateException("无注册执行器: " + cmd.getCmdType()));
                    continue;
                }
                try {
                    handler.execute(cmd);
                    client.markSuccess(cmd);
                } catch (Exception e) {
                    client.markRetryOrFail(cmd, e);
                }
            }
        } finally {
            executingTick = false;
        }
    }

    static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
