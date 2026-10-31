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
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import com.xp.starsite.sequence.generator.SnowflakeIdGenerator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.HdrHistogram.Histogram;

/**
 * sequence 组件基准：发号吞吐 / 多线程竞争 / 唯一性校验。
 *
 * @author muyi
 */
public class SequenceBench {

    /** 每场景迭代次数 */
    public static final int ITERATIONS = 1_000_000;
    public static final int[] THREAD_COUNTS = {1, 4, 8, 16, 32};

    /**
     * S1: 单线程发号基线（纯计算，无 I/O）。
     */
    public static BenchResult singleThread() {
        StarsiteIdGenerator gen = createGenerator(1);
        Histogram hist = new Histogram(3);
        long start = System.nanoTime();

        // 预热
        for (int i = 0; i < 100_000; i++) gen.next();

        Set<Long> ids = new HashSet<>(ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            long id = gen.next();
            long t1 = System.nanoTime();
            hist.recordValue(t1 - t0);
            ids.add(id);
        }
        long elapsed = System.nanoTime() - start;

        return result("S1: single-thread baseline", 1, elapsed, hist, ids, ITERATIONS);
    }

    /**
     * S2: 多线程同 workerId 竞争（总吞吐 + 唯一性）。
     */
    public static BenchResult multiThread(int threads) throws Exception {
        StarsiteIdGenerator gen = createGenerator(1); // 所有线程共享同一 workerId = 最大竞争
        Histogram hist = new Histogram(3);
        Set<Long> ids = java.util.Collections.synchronizedSet(new HashSet<>(ITERATIONS));
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        int perThread = ITERATIONS / threads;

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try { startGate.await(); } catch (InterruptedException e) { return; }
                Set<Long> localIds = new HashSet<>(perThread);
                for (int i = 0; i < perThread; i++) {
                    long t0 = System.nanoTime();
                    long id = gen.next();
                    long t1 = System.nanoTime();
                    synchronized (hist) { hist.recordValue(t1 - t0); }
                    localIds.add(id);
                }
                ids.addAll(localIds);
                doneGate.countDown();
            }).start();
        }

        long start = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long elapsed = System.nanoTime() - start;

        return result("S2: " + threads + "-thread contention", threads, elapsed, hist, ids, threads * perThread);
    }

    /**
     * 创建测试发号器（standalone 形态，固定 workerId）。
     */
    private static StarsiteIdGenerator createGenerator(long workerId) {
        SnowflakeIdGenerator engine = new SnowflakeIdGenerator(
                workerId, 1767225600000L, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        return new StarsiteIdGenerator(engine, workerId, "bench/standalone");
    }

    private static BenchResult result(String scenario, int threads, long elapsedNanos,
                                       Histogram hist, Set<Long> ids, int totalOps) {
        double opsPerSec = totalOps / (elapsedNanos / 1_000_000_000.0);
        boolean unique = ids.size() == totalOps;
        return new BenchResult(scenario, "sequence", threads, totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "standalone mode")
                .pass(unique ? "uniqueness: " + ids.size() + "/" + totalOps + " (100%)"
                        : "DUPLICATES: " + ids.size() + "/" + totalOps)
                .pass(unique ? "no ID collision across " + threads + " threads" : "COLLISION DETECTED");
    }
}
