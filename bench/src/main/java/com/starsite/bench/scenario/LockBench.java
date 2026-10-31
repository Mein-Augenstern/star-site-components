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
import com.starsite.bench.infra.H2Infra;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import com.xp.starsite.lock.store.DbLockClient;
import com.xp.starsite.lock.store.LockTableProvisioner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.HdrHistogram.Histogram;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * lock 组件基准：无竞争获取/释放、多线程竞争、可重入正确性。
 *
 * @author muyi
 */
public class LockBench {

    static final int UNCONTENDED_OPS = 10_000;
    static final int CONTENDED_OPS = 1_000;

    /**
     * 种子行主键发号器（类级共享递增）：全场景共用一张 H2 表，固定 id 会主键冲突——
     * 后到场景的种子 INSERT 失败被 provisioner 吞掉，锁行缺失、tryLock 全部空转
     * （BUG-006 真因之二：曾用固定 () -> 1L 导致 L3 语义校验 0/1000）。
     */
    static final java.util.concurrent.atomic.AtomicLong SEED_ID =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * L1: 无竞争 acquire/release（单线程，锁始终可用）。
     */
    public static BenchResult uncontended(DataSource ds, SqlSessionFactory factory) throws Exception {
        H2Infra.createLockTable(ds);
        ScheduleLockMapper mapper = H2Infra.lockMapper(factory);
        LockTableProvisioner provisioner = new LockTableProvisioner(
                mapper, "starsite_lock", SEED_ID::incrementAndGet);
        DbLockClient lock = new DbLockClient(mapper, provisioner, "starsite_lock", "bench-solo", 30_000);

        Histogram acquireHist = new Histogram(3);
        Histogram releaseHist = new Histogram(3);

        // 预热
        for (int i = 0; i < 100; i++) { lock.tryLock("warmup"); lock.unlock("warmup"); }

        long start = System.nanoTime();
        for (int i = 0; i < UNCONTENDED_OPS; i++) {
            long t0 = System.nanoTime();
            boolean ok = lock.tryLock("bench-lock");
            long t1 = System.nanoTime();
            acquireHist.recordValue(t1 - t0);
            lock.unlock("bench-lock");
            long t2 = System.nanoTime();
            releaseHist.recordValue(t2 - t1);
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = UNCONTENDED_OPS / (elapsed / 1_000_000_000.0);

        return new BenchResult("L1: uncontended acquire/release", "lock", 1,
                UNCONTENDED_OPS, opsPerSec,
                acquireHist.getValueAtPercentile(50), acquireHist.getValueAtPercentile(95),
                acquireHist.getValueAtPercentile(99), acquireHist.getValueAtPercentile(99.9),
                acquireHist.getMaxValue(), "H2 in-memory, acquire latency (release: "
                        + fmt(releaseHist.getValueAtPercentile(50)) + " P50)")
                .pass("all " + UNCONTENDED_OPS + " acquisitions succeeded");
    }

    /**
     * L2: 有竞争 N 线程抢同一锁——度量成功率和等待公平性。
     */
    public static BenchResult contended(DataSource ds, SqlSessionFactory factory, int threads) throws Exception {
        H2Infra.createLockTable(ds);
        AtomicInteger[] winsPerThread = new AtomicInteger[threads];
        Histogram waitHist = new Histogram(3);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        int opsPerThread = CONTENDED_OPS / threads;

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            winsPerThread[t] = new AtomicInteger(0);
            new Thread(() -> {
                try {
                    ScheduleLockMapper mapper = H2Infra.lockMapper(factory);
                    LockTableProvisioner prov = new LockTableProvisioner(mapper, "starsite_lock", SEED_ID::incrementAndGet);
                    DbLockClient lock = new DbLockClient(mapper, prov, "starsite_lock",
                            "thread-" + threadIdx, 30_000);
                    startGate.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        long t0 = System.nanoTime();
                        if (lock.tryLock("contended-lock")) {
                            long t1 = System.nanoTime();
                            synchronized (waitHist) { waitHist.recordValue(t1 - t0); }
                            winsPerThread[threadIdx].incrementAndGet();
                            lock.unlock("contended-lock");
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
                doneGate.countDown();
            }).start();
        }

        long start = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long elapsed = System.nanoTime() - start;

        int totalWins = 0;
        for (AtomicInteger w : winsPerThread) totalWins += w.get();
        double opsPerSec = totalWins / (elapsed / 1_000_000_000.0);

        // 公平性：各线程获得次数的标准差
        double mean = (double) totalWins / threads;
        double variance = 0;
        for (AtomicInteger w : winsPerThread) variance += Math.pow(w.get() - mean, 2);
        double stdDev = Math.sqrt(variance / threads);

        return new BenchResult("L2: " + threads + "-thread contention", "lock", threads,
                totalWins, opsPerSec,
                waitHist.getValueAtPercentile(50), waitHist.getValueAtPercentile(95),
                waitHist.getValueAtPercentile(99), waitHist.getValueAtPercentile(99.9),
                waitHist.getMaxValue(), String.format("fairness σ=%.1f (mean=%.1f)", stdDev, mean))
                .pass("total wins=" + totalWins + "/" + CONTENDED_OPS)
                .pass(stdDev < mean * 0.5 ? "fair: σ=" + String.format("%.1f", stdDev) : "unfair: σ=" + String.format("%.1f", stdDev));
    }

    /**
     * L3: 可重入嵌套——两层 tryLock + 两层 unlock，验证外层仍持锁。
     */
    public static BenchResult reentrant(DataSource ds, SqlSessionFactory factory) throws Exception {
        H2Infra.createLockTable(ds);
        ScheduleLockMapper mapper = H2Infra.lockMapper(factory);
        LockTableProvisioner prov = new LockTableProvisioner(mapper, "starsite_lock", SEED_ID::incrementAndGet);
        DbLockClient lock = new DbLockClient(mapper, prov, "starsite_lock", "reentrant", 30_000);
        DbLockClient other = new DbLockClient(H2Infra.lockMapper(factory),
                new LockTableProvisioner(mapper, "starsite_lock", SEED_ID::incrementAndGet),
                "starsite_lock", "other", 30_000);

        Histogram hist = new Histogram(3);
        int iterations = 1000;

        for (int i = 0; i < 100; i++) { // 预热
            lock.tryLock("reentrant-lock"); lock.tryLock("reentrant-lock");
            lock.unlock("reentrant-lock"); lock.unlock("reentrant-lock");
        }

        long start = System.nanoTime();
        int correct = 0;
        int blockedPhaseFail = 0;
        int acquirePhaseFail = 0;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            lock.tryLock("reentrant-lock");        // 外层
            lock.tryLock("reentrant-lock");        // 内层（重入）
            lock.unlock("reentrant-lock");         // 内层释放（计数递减）
            boolean otherBlocked = !other.tryLock("reentrant-lock"); // 他方仍不可抢
            lock.unlock("reentrant-lock");         // 外层释放（归零）
            boolean otherCanAcquire = other.tryLock("reentrant-lock"); // 他方现在可抢
            if (otherCanAcquire) other.unlock("reentrant-lock");
            long t1 = System.nanoTime();
            hist.recordValue(t1 - t0);
            if (otherBlocked && otherCanAcquire) correct++;
            if (!otherBlocked) blockedPhaseFail++;
            if (!otherCanAcquire) acquirePhaseFail++;
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iterations / (elapsed / 1_000_000_000.0);

        return new BenchResult("L3: reentrant nested (2-level)", "lock", 2,
                iterations, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "H2 in-memory, full cycle latency")
                .pass(correct == iterations ? "reentrant semantics: " + correct + "/" + iterations + " correct"
                        : "REENTRANT FAILURE: " + correct + "/" + iterations
                                + " (blockedPhase=" + blockedPhaseFail + ", acquirePhase=" + acquirePhaseFail + ")");
    }

    private static String fmt(long nanos) {
        if (nanos < 1_000) return nanos + "ns";
        if (nanos < 1_000_000) return String.format("%.0fμs", nanos / 1000.0);
        return String.format("%.1fms", nanos / 1_000_000.0);
    }
}
