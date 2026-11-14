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
import com.xp.starsite.schedule.trigger.CronTrigger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;

/**
 * schedule 组件基准：窗口命中判定（纯时间函数）——tuner 窗口同款语义
 * {@code startCron.nextAfter(t) > endCron.nextAfter(t)}，单 op 两次 cron nextAfter 求值。
 *
 * @author muyi
 */
public class ScheduleBench {

    /** 每场景迭代次数（可被 1440 整除——分钟粒度铺满完整天循环） */
    public static final int ITERATIONS = 1_008_000;

    /** 一天分钟数 */
    static final int MINUTES_PER_DAY = 1440;

    /** 窗口 [08:00, 18:00) 的分钟跨度 */
    static final int WINDOW_START_MIN = 8 * 60;
    static final int WINDOW_END_MIN = 18 * 60;

    /** 窗口触发器（CronExpression 构造期定稿不可变——多线程共享安全） */
    static final CronTrigger START = new CronTrigger("0 0 8 * * ?", ZoneId.of("Asia/Shanghai"));
    static final CronTrigger END = new CronTrigger("0 0 18 * * ?", ZoneId.of("Asia/Shanghai"));

    /** 固定基准日（窗口命中只依赖一天内时刻——日期任意，确定性校验用） */
    static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    /**
     * SC1: 单线程窗口命中判定吞吐。
     */
    public static BenchResult windowHitSingle() {
        Histogram hist = new Histogram(3);
        AtomicInteger hits = new AtomicInteger(0);

        // 预热
        for (int i = 0; i < 100_000; i++) {
            hit(BASE.plusMinutes(i % MINUTES_PER_DAY));
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            LocalDateTime t = BASE.plusMinutes(i % MINUTES_PER_DAY);
            long t0 = System.nanoTime();
            boolean inWindow = hit(t);
            long t1 = System.nanoTime();
            hist.recordValue(t1 - t0);
            if (inWindow) {
                hits.incrementAndGet();
            }
        }
        long elapsed = System.nanoTime() - start;

        return result("SC1: window-hit single-thread", 1, elapsed, hist, hits.get(), ITERATIONS);
    }

    /**
     * SC2: 多线程窗口命中判定（共享触发器——只读并发安全）。
     *
     * @param threads 线程数
     */
    public static BenchResult windowHitThreads(int threads) throws Exception {
        Histogram hist = new Histogram(3);
        AtomicInteger hits = new AtomicInteger(0);
        AtomicLong cursor = new AtomicLong(0); // 全局分钟游标：各线程消费不同时刻
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        int perThread = ITERATIONS / threads;

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        LocalDateTime time = BASE.plusMinutes(
                                (int) (cursor.getAndIncrement() % MINUTES_PER_DAY));
                        long t0 = System.nanoTime();
                        boolean inWindow = hit(time);
                        long t1 = System.nanoTime();
                        synchronized (hist) { hist.recordValue(t1 - t0); }
                        if (inWindow) {
                            hits.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                doneGate.countDown();
            }).start();
        }

        long start = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long elapsed = System.nanoTime() - start;

        return result("SC2: window-hit " + threads + "-thread", threads, elapsed, hist,
                hits.get(), perThread * threads);
    }

    /**
     * 单次窗口命中判定：下次开始晚于下次结束 ⟺ 当前在窗口内。
     *
     * @param t 判定时刻
     * @return true 在窗口内
     */
    static boolean hit(LocalDateTime t) {
        return START.nextAfter(t).isAfter(END.nextAfter(t));
    }

    /**
     * 确定性期望命中数（分钟粒度循环铺天）。
     *
     * @param total 总判定次数
     * @return 期望命中次数
     */
    static int expectedHits(int total) {
        int expected = total / MINUTES_PER_DAY * (WINDOW_END_MIN - WINDOW_START_MIN);
        int remainder = total % MINUTES_PER_DAY;
        for (int m = 0; m < remainder; m++) {
            if (m >= WINDOW_START_MIN && m < WINDOW_END_MIN) {
                expected++;
            }
        }
        return expected;
    }

    private static BenchResult result(String scenario, int threads, long elapsedNanos,
                                       Histogram hist, int hits, int totalOps) {
        double opsPerSec = totalOps / (elapsedNanos / 1_000_000_000.0);
        int expected = expectedHits(totalOps);
        return new BenchResult(scenario, "schedule", threads, totalOps, opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), "cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op")
                .pass(hits == expected
                        ? "window semantics: " + hits + "/" + totalOps + " hits (expected " + expected + ")"
                        : "WINDOW MISMATCH: " + hits + " hits, expected " + expected);
    }
}
