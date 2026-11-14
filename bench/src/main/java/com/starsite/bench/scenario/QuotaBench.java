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
import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.core.QuotaEngine;
import com.xp.starsite.quota.store.memory.MemoryQuotaStore;
import java.util.concurrent.CountDownLatch;
import org.HdrHistogram.Histogram;

/**
 * quota 组件基准（memory 形态）：occupy/release 往返——存储端不变式条件 UPDATE 的
 * 内存等价物（条件不中 → Optional.empty() → 引擎定责）。收尾校验账面归位不变式。
 *
 * @author muyi
 */
public class QuotaBench {

    /** occupy/release 往返次数 */
    public static final int OPS = 500_000;

    /** 配额键 */
    static final String KEY = "bench-quota";

    /** 账面总量 */
    static final long TOTAL = 100_000L;

    /**
     * Q1: occupy(1) + release(1) 往返。
     *
     * @param threads 线程数（1 = 单线程基线；16 = 同账户争抢）
     */
    public static BenchResult occupyRelease(int threads) throws Exception {
        QuotaEngine engine = new QuotaEngine(new MemoryQuotaStore());
        engine.init(KEY, TOTAL);
        Histogram hist = new Histogram(3);
        int perThread = OPS / threads;

        // 预热
        for (int i = 0; i < 10_000; i++) {
            engine.occupy(KEY, 1);
            engine.release(KEY, 1);
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        long t0 = System.nanoTime();
                        engine.occupy(KEY, 1);
                        engine.release(KEY, 1);
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
        QuotaBalance balance = engine.balance(KEY).orElseThrow();
        boolean invariant = balance.getAvailable() == TOTAL
                && balance.getOccupied() == 0 && balance.getFrozen() == 0;
        return new BenchResult("Q1: occupy/release round-trip", "quota", threads,
                totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "Memory store, same account, amount=1")
                .pass(invariant
                        ? "invariant restored: available=" + balance.getAvailable()
                                + " occupied=0 frozen=0"
                        : "INVARIANT BROKEN: available=" + balance.getAvailable()
                                + " occupied=" + balance.getOccupied()
                                + " frozen=" + balance.getFrozen());
    }
}
