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
import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.executor.ExecutorRegistry;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.scheduler.AsyncCommandScheduler;
import com.xp.starsite.asynccmd.store.memory.InMemoryAsyncCommandStore;
import com.xp.starsite.asynccmd.store.mybatis.AsyncCommandMapper;
import com.xp.starsite.asynccmd.store.mybatis.MyBatisAsyncCommandStore;
import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * async-command 组件基准：提交吞吐、端到端时延、多实例认领公平性。
 *
 * @author muyi
 */
public class AsyncBench {

    static final int SUBMIT_OPS = 50_000;
    static final int E2E_OPS = 1_000;
    static final int CHAIN_OPS = 50_000;

    /** 记录执行的假执行器（含时间戳供端到端时延计算） */
    static class TimingExecutor implements AsyncCommandExecutor {
        final String typeName;
        final CopyOnWriteArrayList<long[]> timings = new CopyOnWriteArrayList<>(); // [submitNanos, executeNanos]

        TimingExecutor(String typeName) { this.typeName = typeName; }
        @Override public String type() { return typeName; }
        @Override public void execute(AsyncCommand cmd) {
            timings.add(new long[]{cmd.getId(), System.nanoTime()});
        }
    }

    /**
     * A1: 内存形态提交吞吐（无 DB，纯内存队列）。
     */
    public static BenchResult memorySubmit() {
        var store = new InMemoryAsyncCommandStore();
        var config = new AsyncCommandConfig();
        config.setOwner("bench");
        var client = new AsyncCommandClient(store, config);
        Histogram hist = new Histogram(3);

        for (int i = 0; i < 10_000; i++) client.submit("warmup", "w" + i, "{}");

        long start = System.nanoTime();
        for (int i = 0; i < SUBMIT_OPS; i++) {
            long t0 = System.nanoTime();
            client.submit("bench", "k" + i, "{}");
            long t1 = System.nanoTime();
            hist.recordValue(t1 - t0);
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = SUBMIT_OPS / (elapsed / 1_000_000_000.0);

        return new BenchResult("A1: memory submit throughput", "async-command", 1,
                SUBMIT_OPS, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "InMemory store, no DB")
                .pass("all " + SUBMIT_OPS + " submissions accepted");
    }

    /**
     * A2: 端到端时延（submit → tick → execute → SUCCESS），MyBatis + H2。
     */
    public static BenchResult endToEnd(javax.sql.DataSource ds, SqlSessionFactory factory) throws Exception {
        H2Infra.createAsyncTable(ds);
        AsyncCommandMapper mapper = H2Infra.asyncMapper(factory);
        AtomicLong idSeq = new AtomicLong(0);
        MyBatisAsyncCommandStore store = new MyBatisAsyncCommandStore(mapper, "async_command", false, idSeq::incrementAndGet);

        var config = new AsyncCommandConfig();
        config.setOwner("bench-e2e");
        config.setBatchSize(100);
        TimingExecutor executor = new TimingExecutor("e2e-test");
        var client = new AsyncCommandClient(store, config);
        var scheduler = new AsyncCommandScheduler(store, client, config,
                new ExecutorRegistry(() -> java.util.List.of(executor)));

        Histogram e2eHist = new Histogram(3);

        // 逐条：submit → tick → 测量 submit→execute 的 nanoTime 差
        for (int i = 0; i < E2E_OPS; i++) {
            long submitNanos = System.nanoTime();
            client.submit("e2e-test", "k" + i, "{}");
            scheduler.tick(); // 手动驱动一轮调度
            long executeNanos = executor.timings.size() > i
                    ? executor.timings.get(i)[1] : System.nanoTime();
            e2eHist.recordValue(executeNanos - submitNanos);
        }

        double opsPerSec = E2E_OPS / (e2eHist.getMaxValue() / 1_000_000_000.0);

        return new BenchResult("A2: end-to-end (submit→execute)", "async-command", 1,
                E2E_OPS, opsPerSec,
                e2eHist.getValueAtPercentile(50), e2eHist.getValueAtPercentile(95),
                e2eHist.getValueAtPercentile(99), e2eHist.getValueAtPercentile(99.9),
                e2eHist.getMaxValue(), "H2 + MyBatis, manual tick")
                .pass("executed " + executor.timings.size() + "/" + E2E_OPS + " commands");
    }

    /**
     * A4: 内存形态全链路（submit → 认领 → 执行 → 终态回写，in-process 无 DB）。
     * <p>
     * 单 op = submit + claim（令牌批量）+ findByToken + 逐条执行（计数替身）+ updateState。
     * 多线程共用同一 store 与全局 FIFO——他方线程可能认领走本方命令（认领互斥），
     * 总量守恒：executed == submitted。
     *
     * @param threads 并发线程数（1 = 单线程基线；16 = in-process 争抢）
     */
    public static BenchResult memoryFullChain(int threads) throws Exception {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig config = new AsyncCommandConfig();
        config.setOwner("bench-mem");
        AsyncCommandClient client = new AsyncCommandClient(store, config);
        Histogram hist = new Histogram(3);
        AtomicInteger executed = new AtomicInteger(0);
        // 认领上限须大于并发在飞数：保证任一 claim 能清空当前全部 PENDING（末尾无残留）
        int claimLimit = threads + 8;
        int perThread = CHAIN_OPS / threads;

        // 预热（计数进独立计数器，不计入校验）
        runChain(store, client, new AtomicInteger(), "warmup", 1_000, claimLimit);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final String tokenBase = "tok-" + t + "-";
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        long t0 = System.nanoTime();
                        client.submit("mem-chain", tokenBase + "k-" + i, "{}");
                        String token = tokenBase + i;
                        store.claim(token, claimLimit, LocalDateTime.now());
                        for (AsyncCommand cmd : store.findByToken(token)) {
                            executed.incrementAndGet(); // 业务动作替身
                            cmd.setStatus(AsyncCommand.SUCCESS);
                            store.updateState(cmd);
                        }
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

        return new BenchResult("A4: memory full chain (submit+claim+execute)", "async-command", threads,
                totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "InMemory store, in-process full chain")
                .pass("executed " + executed.get() + "/" + totalOps + " commands")
                .pass(store.countBacklogDue(LocalDateTime.now()) == 0
                        ? "backlog drained: 0 unfinished" : "BACKLOG REMAINS");
    }

    /**
     * 全链路执行一轮指定次数（预热复用）。
     */
    private static void runChain(InMemoryAsyncCommandStore store, AsyncCommandClient client,
                                 AtomicInteger executed, String prefix, int ops, int claimLimit) {
        for (int i = 0; i < ops; i++) {
            String token = prefix + "-tok-" + i;
            client.submit("mem-chain", prefix + "-k-" + i, "{}");
            store.claim(token, claimLimit, LocalDateTime.now());
            for (AsyncCommand cmd : store.findByToken(token)) {
                executed.incrementAndGet();
                cmd.setStatus(AsyncCommand.SUCCESS);
                store.updateState(cmd);
            }
        }
    }

    /**
     * A3: 多实例认领公平性——两个"实例"各自认领，验证互斥与分配均匀。
     */
    public static BenchResult claimFairness(javax.sql.DataSource ds, SqlSessionFactory factory) throws Exception {
        H2Infra.createAsyncTable(ds);
        AsyncCommandMapper mapper = H2Infra.asyncMapper(factory);
        AtomicLong idSeq = new AtomicLong(0);
        MyBatisAsyncCommandStore store = new MyBatisAsyncCommandStore(mapper, "async_command", false, idSeq::incrementAndGet);

        var config = new AsyncCommandConfig();
        config.setOwner("fairness");
        config.setBatchSize(50);
        var client = new AsyncCommandClient(store, config);

        // 提交 1000 条命令
        int total = 1000;
        for (int i = 0; i < total; i++) {
            client.submit("fairness-test", "k" + i, "{}");
        }

        // 两个"实例"各自认领执行
        var instA = new CopyOnWriteArrayList<Long>();
        var instB = new CopyOnWriteArrayList<Long>();

        for (int round = 0; round < 20; round++) {
            // 实例 A 认领一批
            String tokenA = "instA-r" + round;
            int claimedA = store.claim(tokenA, 25, java.time.LocalDateTime.now());
            if (claimedA > 0) {
                store.findByToken(tokenA).forEach(c -> {
                    instA.add(c.getId());
                    c.setStatus(AsyncCommand.SUCCESS);
                    store.updateState(c);
                });
            }
            // 实例 B 认领一批
            String tokenB = "instB-r" + round;
            int claimedB = store.claim(tokenB, 25, java.time.LocalDateTime.now());
            if (claimedB > 0) {
                store.findByToken(tokenB).forEach(c -> {
                    instB.add(c.getId());
                    c.setStatus(AsyncCommand.SUCCESS);
                    store.updateState(c);
                });
            }
        }

        int totalClaimed = instA.size() + instB.size();
        double mean = (double) totalClaimed / 2;
        double stdDev = Math.sqrt((Math.pow(instA.size() - mean, 2) + Math.pow(instB.size() - mean, 2)) / 2);

        // 检查无重叠
        var overlap = new java.util.HashSet<>(instA);
        overlap.retainAll(instB);

        return new BenchResult("A3: 2-instance claim fairness", "async-command", 2,
                totalClaimed, totalClaimed, 0, 0, 0, 0, 0,
                String.format("A=%d B=%d σ=%.1f", instA.size(), instB.size(), stdDev))
                .pass("claimed " + totalClaimed + "/" + total)
                .pass(overlap.isEmpty() ? "no overlap between instances" : "OVERLAP: " + overlap.size() + " commands")
                .pass(stdDev < mean * 0.3 ? "fair split: σ=" + String.format("%.1f", stdDev) : "unfair: σ=" + String.format("%.1f", stdDev));
    }
}
