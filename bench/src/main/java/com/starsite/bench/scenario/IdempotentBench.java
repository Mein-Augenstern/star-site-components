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
import com.xp.starsite.idempotent.api.IdempotentResult;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.core.IdempotentEngine;
import com.xp.starsite.idempotent.store.memory.MemoryIdempotentStore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;

/**
 * idempotent 组件基准（memory 形态）：guard() 首次执行 vs 回放两路径吞吐，
 * 以及 16 线程同键竞争的恰一执行语义。
 *
 * @author muyi
 */
public class IdempotentBench {

    /** 首次执行/回放两场景各自的键数 */
    public static final int EXEC_OPS = 100_000;
    public static final int REPLAY_OPS = 100_000;

    /** 同键竞争场景：线程数 × 轮数 */
    public static final int RACE_THREADS = 16;
    public static final int RACE_ROUNDS = 50;

    /**
     * 构建幂等引擎（memory store + 家族缺省窗口参数）。
     */
    static IdempotentEngine engine() {
        MemoryIdempotentStore store = new MemoryIdempotentStore(new AtomicLong()::incrementAndGet);
        return new IdempotentEngine(store, TimeSource.system(), Duration.ofHours(24),
                Duration.ofMillis(5000), 100, Duration.ofSeconds(120));
    }

    /**
     * I1: 首次执行路径（认领 → 执行 → complete）：每 op 独立键，全量 EXECUTED。
     *
     * @param threads 线程数（1 = 单线程基线）
     */
    public static BenchResult firstExec(int threads) throws Exception {
        IdempotentEngine engine = engine();
        Histogram hist = new Histogram(3);
        AtomicInteger executed = new AtomicInteger(0);
        int perThread = EXEC_OPS / threads;

        // 预热（独立 scope 不污染计量）
        for (int i = 0; i < 10_000; i++) {
            engine.guard("bench-warm", "w" + i, () -> "ok");
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final String ns = "t" + t + "-";
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        long t0 = System.nanoTime();
                        IdempotentResult r = engine.guard("bench-exec", ns + i, () -> "ok");
                        long t1 = System.nanoTime();
                        if (r.isExecuted()) {
                            executed.incrementAndGet();
                        }
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
        return new BenchResult("I1: first-exec (claim+execute+complete)", "idempotent", threads,
                totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "Memory store, fresh key per op")
                .pass("exactly-executed " + executed.get() + "/" + totalOps);
    }

    /**
     * I2: 回放路径（DONE 行命中 → 结果回放，动作零执行）。
     * 先逐键恰一执行预置 DONE 行，再计量回放吞吐。
     *
     * @param threads 线程数（1 = 单线程基线）
     */
    public static BenchResult replay(int threads) throws Exception {
        IdempotentEngine engine = engine();
        Histogram hist = new Histogram(3);
        AtomicInteger actionRuns = new AtomicInteger(0);
        AtomicInteger replayed = new AtomicInteger(0);
        int perThread = REPLAY_OPS / threads;

        // 预置：每键恰一执行（DONE 行；不计量）
        for (int i = 0; i < REPLAY_OPS; i++) {
            engine.guard("bench-replay", "k" + i, () -> "first");
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        long t0 = System.nanoTime();
                        IdempotentResult r = engine.guard("bench-replay", "k" + (base + i),
                                () -> {
                                    actionRuns.incrementAndGet(); // 回放路径不应触达
                                    return "re-exec";
                                });
                        long t1 = System.nanoTime();
                        if (r.isReplayed()) {
                            replayed.incrementAndGet();
                        }
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
        return new BenchResult("I2: replay (DONE hit, zero action)", "idempotent", threads,
                totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "Memory store, pre-executed keys")
                .pass("replayed " + replayed.get() + "/" + totalOps)
                .pass(actionRuns.get() == 0
                        ? "action zero-execution on replay path"
                        : "REPLAY LEAK: action ran " + actionRuns.get() + " times");
    }

    /**
     * I3: 16 线程同键齐发——恰一执行语义（恰一 EXECUTED，其余 REPLAYED，零 CONFLICT）。
     * 回放方经 100ms 轮询等待在飞者完成后回放（长尾由等待节奏决定，见 notes）。
     */
    public static BenchResult exactlyOnceRace() throws Exception {
        IdempotentEngine engine = engine();
        Histogram hist = new Histogram(3);
        AtomicInteger actionRuns = new AtomicInteger(0);
        AtomicInteger executed = new AtomicInteger(0);
        AtomicInteger replayed = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        long start = System.nanoTime();
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String key = "race-" + round;
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(RACE_THREADS);
            for (int t = 0; t < RACE_THREADS; t++) {
                new Thread(() -> {
                    try {
                        go.await();
                        long t0 = System.nanoTime();
                        IdempotentResult r = engine.guard("bench-race", key, () -> {
                            actionRuns.incrementAndGet();
                            return "payload";
                        });
                        long t1 = System.nanoTime();
                        synchronized (hist) { hist.recordValue(t1 - t0); }
                        switch (r.getKind()) {
                            case EXECUTED -> executed.incrementAndGet();
                            case REPLAYED -> replayed.incrementAndGet();
                            case CONFLICT -> conflicts.incrementAndGet();
                            default -> { }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    done.countDown();
                }).start();
            }
            go.countDown();
            done.await();
        }
        long elapsed = System.nanoTime() - start;
        int totalOps = RACE_ROUNDS * RACE_THREADS;

        double opsPerSec = totalOps / (elapsed / 1_000_000_000.0);
        return new BenchResult("I3: exactly-once race (same key)", "idempotent", RACE_THREADS,
                totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "wait-poll 100ms dominates replay tail")
                .pass(executed.get() == RACE_ROUNDS
                        ? "exactly-once: 1 EXECUTED x " + RACE_ROUNDS + " rounds"
                        : "EXACTLY-ONCE BROKEN: " + executed.get() + " executed")
                .pass(actionRuns.get() == RACE_ROUNDS
                        ? "action ran exactly once per key: " + actionRuns.get() + "/" + RACE_ROUNDS
                        : "ACTION MULTI-RUN: " + actionRuns.get())
                .pass(conflicts.get() == 0
                        ? "zero conflicts (wait window sufficed)"
                        : conflicts.get() + " conflicts")
                .pass(replayed.get() == RACE_ROUNDS * (RACE_THREADS - 1)
                        ? "others replayed: " + replayed.get()
                        : "replay count " + replayed.get());
    }
}
