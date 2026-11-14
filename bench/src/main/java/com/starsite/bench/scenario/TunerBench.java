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
package com.starsite.bench.scenario;

import com.starsite.bench.BenchResult;
import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.core.TunerEngine;
import com.xp.starsite.tuner.core.TunerOptions;
import com.xp.starsite.tuner.store.memory.MemoryTunerStore;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

/**
 * tuner 组件基准（memory 形态 + 已注册池）：applyAll() 稳态求值——每轮对全部受管池
 * 做窗口命中判定（startCron/endCron 各一次 nextAfter）+ 条件应用 no-op 跳过
 * （账面已到位不重复 set，审计零增长）。
 *
 * @author muyi
 */
public class TunerBench {

    /** 受管池数（每轮 applyAll 逐池求值） */
    public static final int POOL_COUNT = 4;

    /** applyAll 调用次数 */
    public static final int OPS = 100_000;

    /** 常态命中窗口（00:00:00 ~ 23:59:00）：稳态每轮都走窗口求值路径 */
    static final String WINDOW_START_CRON = "0 0 0 * * ?";
    static final String WINDOW_END_CRON = "0 59 23 * * ?";

    /** 引擎 + 受管池持有（校验稳态参数与审计用） */
    static final class EngineSetup {
        final TunerEngine engine;
        final ThreadPoolExecutor[] pools;

        EngineSetup(TunerEngine engine, ThreadPoolExecutor[] pools) {
            this.engine = engine;
            this.pools = pools;
        }
    }

    /**
     * T1: applyAll() 稳态吞吐（注册期 STARTUP 对齐后，每轮纯求值 + 跳过）。
     *
     * @param threads 线程数（1 = 单线程基线；16 = 三路 applyAll 并发，per-pool 锁串行化）
     */
    public static BenchResult applyAll(int threads) throws Exception {
        EngineSetup setup = setup();
        Histogram hist = new Histogram(3);
        int perThread = OPS / threads;

        // 预热（进入稳态：lastApplied 对齐 → 条件应用跳过）
        for (int i = 0; i < 1_000; i++) {
            setup.engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        }
        int auditsBefore = setup.engine.recentAudits(1_000).size();
        int[][] paramsBefore = params(setup.pools);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        long t0 = System.nanoTime();
                        setup.engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
                        long t1 = System.nanoTime();
                        synchronized (hist) { hist.recordValue(t1 - t0); }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                doneGate.countDown();
            }).start();
        }

        long start = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long elapsed = System.nanoTime() - start;
        int totalOps = perThread * threads;

        double opsPerSec = totalOps / (elapsed / 1_000_000_000.0);
        int auditsAfter = setup.engine.recentAudits(1_000).size();
        int[][] paramsAfter = params(setup.pools);

        return new BenchResult("T1: applyAll steady-state", "tuner", threads,
                totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), POOL_COUNT + " pools, daily-hit window, no-op skip")
                .pass(Arrays.deepEquals(paramsBefore, paramsAfter)
                        ? "steady-state params stable (core/max/keep unchanged)"
                        : "PARAMS DRIFTED: " + Arrays.deepToString(paramsAfter))
                .pass(auditsAfter == auditsBefore
                        ? "no spurious applies: audits " + auditsBefore + " -> " + auditsAfter
                        : "AUDIT GROWTH: " + auditsBefore + " -> " + auditsAfter);
    }

    /**
     * 装配：memory store + 双预案（基线/窗口）+ 每池常态命中窗口，注册即 STARTUP 对齐。
     */
    static EngineSetup setup() {
        MemoryTunerStore store = new MemoryTunerStore(LocalDateTime::now);
        TunerEngine engine = new TunerEngine(store, LocalDateTime::now,
                ZoneId.of("Asia/Shanghai"), TunerOptions.defaults());
        engine.upsertPreset(new TunerPreset("bench-baseline", 2, 4, 60));
        engine.upsertPreset(new TunerPreset("bench-window", 8, 16, 30));
        ThreadPoolExecutor[] pools = new ThreadPoolExecutor[POOL_COUNT];
        for (int i = 0; i < POOL_COUNT; i++) {
            String name = "bench-pool-" + i;
            engine.bindDefault(name, "bench-baseline");
            engine.upsertWindow(new TunerWindow(name, "bench-window",
                    WINDOW_START_CRON, WINDOW_END_CRON));
            pools[i] = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>());
            engine.register(name, pools[i]); // 注册即求值（窗口命中 → bench-window 生效）
        }
        return new EngineSetup(engine, pools);
    }

    /**
     * 各池当前参数快照（core/max/keepAlive 秒）。
     */
    private static int[][] params(ThreadPoolExecutor[] pools) {
        int[][] out = new int[pools.length][];
        for (int i = 0; i < pools.length; i++) {
            out[i] = new int[]{pools[i].getCorePoolSize(), pools[i].getMaximumPoolSize(),
                    (int) pools[i].getKeepAliveTime(TimeUnit.SECONDS)};
        }
        return out;
    }
}
