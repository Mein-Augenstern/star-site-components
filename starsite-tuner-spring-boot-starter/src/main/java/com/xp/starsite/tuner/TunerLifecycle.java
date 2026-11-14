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
package com.xp.starsite.tuner;

import com.xp.starsite.tuner.core.TunerEngine;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 轮询生命周期（SmartLifecycle，phase MAX 最后启动最先停止）：周期驱动引擎求值——
 * 空轮询成本 ≈ 一次 versionStamp 查询（配置未变则只做窗口求值，时间自变量每轮都要看）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TunerLifecycle.class);

    /** 引擎 */
    private final TunerEngine engine;

    /** 轮询间隔秒 */
    private final int pollIntervalSeconds;

    /** 调度器（显式创建——P3C【强制】，家族 P3C 巡检规约） */
    private ScheduledThreadPoolExecutor scheduler;

    /** 运行标志 */
    private volatile boolean running = false;

    /**
     * 构造。
     *
     * @param engine             引擎
     * @param pollIntervalSeconds 轮询间隔秒
     */
    public TunerLifecycle(TunerEngine engine, int pollIntervalSeconds) {
        this.engine = engine;
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        if (running) {
            return;
        }
        java.util.concurrent.ThreadFactory factory = new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "starsite-tuner-poll-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        scheduler = new ScheduledThreadPoolExecutor(1, factory);
        scheduler.scheduleWithFixedDelay(this::pollCycle, pollIntervalSeconds,
                pollIntervalSeconds, TimeUnit.SECONDS);
        running = true;
        log.info("[starsite-tuner] 轮询启动（间隔 {}s）", pollIntervalSeconds);
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

    /** 单轮求值（异常吞掉下轮再试——治理轮询不许影响业务线程） */
    private void pollCycle() {
        try {
            engine.applyAll(com.xp.starsite.tuner.api.TunerAuditEntry.Source.SCHEDULE);
        } catch (Exception e) {
            log.warn("[starsite-tuner] 轮询失败（下轮重试）: {}", e.getMessage());
        }
    }
}
