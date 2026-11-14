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
package com.xp.starsite.idempotent;

import com.xp.starsite.idempotent.api.IdempotentClient;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 过期行清理看护（SmartLifecycle 托管，phase MAX 最后启动最先停止）：
 * 周期调用 {@link IdempotentClient#purgeExpired}——expire_at 过期行语义已死
 * （回放/回收判定不受影响），清理纯表卫生；满批连续追赶（单窗口最多 20 轮，
 * 与 async-command retention 同口径——积压单窗口清光）。
 *
 * <p>与 async-command retention 的差异：那边默认关（不动用户业务数据的保守默认），
 * 这边默认开——幂等记录是瞬态去重状态而非业务数据，且 expire_at 过期即语义死行，
 * 无限累积是 OBS-111 的同款教训提前规避。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentRetentionLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRetentionLifecycle.class);

    /** 单窗口满批追赶上限（async-command retention 同口径） */
    private static final int MAX_ROUNDS_PER_WINDOW = 20;

    /** 幂等门面（清理入口） */
    private final IdempotentClient client;

    /** 清理间隔 */
    private final java.time.Duration interval;

    /** 单轮批量 */
    private final int batchSize;

    /** 看护调度器（单线程守护——低频清理不值得更多） */
    private ScheduledExecutorService scheduler;

    /** 运行标志 */
    private volatile boolean running = false;

    /**
     * 构造看护。
     *
     * @param client    幂等门面
     * @param interval  清理间隔（分钟级——低频卫生任务）
     * @param batchSize 单轮批量上限
     */
    public IdempotentRetentionLifecycle(IdempotentClient client, java.time.Duration interval,
                                        int batchSize) {
        this.client = client;
        this.interval = interval;
        this.batchSize = Math.max(1, batchSize);
    }

    /** {@inheritDoc} 守护线程周期调度（首跑延迟一个完整间隔——启动期不做清理 IO） */
    @Override
    public void start() {
        if (running) {
            return;
        }
        // 显式 Executor 创建（阿里 P3C【强制】：禁 Executors 快捷方法）——单线程守护语义等价
        java.util.concurrent.ThreadFactory factory = new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger seq =
                    new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "starsite-idempotent-retention-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1, factory);
        scheduler.scheduleWithFixedDelay(this::purgeWindow, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
        log.info("[starsite-idempotent] 过期清理看护启动（间隔 {} 分钟，批量 {}）",
                interval.toMinutes(), batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        running = false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** {@inheritDoc} */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * 单窗口清理（满批追赶：返回满批即连续再清，上限 {@value #MAX_ROUNDS_PER_WINDOW} 轮）。
     */
    private void purgeWindow() {
        try {
            int rounds = 0;
            long removed;
            do {
                removed = client.purgeExpired(batchSize);
                rounds++;
            } while (removed >= batchSize && rounds < MAX_ROUNDS_PER_WINDOW);
            if (removed > 0 || rounds > 1) {
                log.info("[starsite-idempotent] 清理窗口完成（{} 轮）", rounds);
            }
        } catch (Exception e) {
            // 清理失败只记 warn 下窗口再试——卫生任务不许影响业务路径
            log.warn("[starsite-idempotent] 清理窗口失败（下窗口重试）: {}", e.getMessage());
        }
    }
}
