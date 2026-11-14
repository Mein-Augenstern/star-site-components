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
package com.xp.starsite.lock.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 看门狗锁包装器回归矩阵（P0 修复）：
 * 重入内层 unlock 不停看门狗（深度计数）/ 全释放后续期停止 / 并发首抢恰一注册
 * （putIfAbsent）/ 续期失败回调 LockLostListener 并停狗。
 * <p>
 * 修复前两条 P0：内层 unlock 无条件停狗——外层仍在临界区但续期已停，长任务超 TTL
 * 后锁被抢占互斥失效；containsKey+put 双步注册——并发首抢泄漏多余续期任务。
 *
 * @author muyi
 * @date 2026/11/14
 */
class WatchdogLockClientTest {

    /** 续期间隔下限（毫秒）——被测类 min(ttl/3, 1000) 的下限，等待断言按此上界设计 */
    private static final long RENEW_FLOOR_MS = 1000;

    /**
     * 可编程假锁：无限重入（tryLock 恒 true——看门狗包装器的 delegate 语义），
     * 记录每次调用供断言；可切换为"续期失败"模式（首次之后 tryLock 恒 false）。
     */
    private static final class RecordingLockClient implements LockClient {

        /** tryLock 调用总次数（含首抢与续期） */
        final AtomicInteger tryLocks = new AtomicInteger();

        /** unlock 调用总次数 */
        final AtomicInteger unlocks = new AtomicInteger();

        /** true = 首次争锁成功后后续全部失败（模拟锁被他方抢占，触发续期失败路径） */
        volatile boolean failAfterFirstAcquire = false;

        @Override
        public boolean tryLock(String lockName) {
            if (failAfterFirstAcquire && tryLocks.get() > 0) {
                return false;
            }
            tryLocks.incrementAndGet();
            return true;
        }

        @Override
        public void unlock(String lockName) {
            unlocks.incrementAndGet();
        }
    }

    /** 并发线程池（并发首抢用例） */
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** 收尾：关被测包装器（停看门狗线程）与线程池 */
    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /**
     * P0-1 回归：重入内层 unlock 只递减深度，看门狗必须存活到外层释放。
     * 修复前：内层 unlock 无条件停狗，activeWatchCount 归零，外层临界区裸奔。
     */
    @Test
    void 重入内层unlock_看门狗存活() {
        RecordingLockClient delegate = new RecordingLockClient();
        try (WatchdogLockClient watchdog = new WatchdogLockClient(delegate, 30_000, null)) {
            assertThat(watchdog.tryLock("job")).isTrue();
            assertThat(watchdog.tryLock("job")).isTrue(); // 重入 +1 层
            assertThat(watchdog.activeWatchCount()).isEqualTo(1);

            watchdog.unlock("job"); // 内层释放：深度 2→1，看门狗不许停
            assertThat(watchdog.activeWatchCount())
                    .as("内层 unlock 后看门狗必须仍在（外层仍在临界区）").isEqualTo(1);

            watchdog.unlock("job"); // 外层释放：深度归零，看门狗停止
            assertThat(watchdog.activeWatchCount()).isEqualTo(0);
        }
    }

    /**
     * P0-1 端到端：内层 unlock 之后续期仍在发生（真续期证明，非仅计数断言）；
     * 全部释放后续期停止（无泄漏续期任务持续唤醒）。
     */
    @Test
    void 内层unlock后续期继续_全释放后续期停止() throws Exception {
        RecordingLockClient delegate = new RecordingLockClient();
        // TTL 3000 → 续期间隔 = min 下限 1000ms（3000/3 恰触底），等待窗口按此设计
        try (WatchdogLockClient watchdog = new WatchdogLockClient(delegate, 3_000, null)) {
            watchdog.tryLock("job");
            watchdog.tryLock("job");
            watchdog.unlock("job"); // 内层释放——此时看门狗必须继续续期

            Thread.sleep(RENEW_FLOOR_MS + 500); // 至少一轮续期窗口
            int afterInner = delegate.tryLocks.get();
            assertThat(afterInner).as("内层 unlock 后续期仍在发生").isGreaterThan(2);

            watchdog.unlock("job"); // 外层释放——看门狗停止
            Thread.sleep(200); // 在途续期结算余波（cancel 不中断已运行任务）
            int baseline = delegate.tryLocks.get();
            Thread.sleep(RENEW_FLOOR_MS + 500);
            assertThat(delegate.tryLocks.get()).as("全释放后续期必须停止").isEqualTo(baseline);
        }
    }

    /**
     * P0-2 回归：并发首抢同锁名恰一注册（putIfAbsent 原子性）——无论多少线程同时
     * 通过首抢，活跃看门狗数恒为 1；全释放后归零。
     */
    @Test
    void 并发首抢_恰一注册看门狗() throws Exception {
        RecordingLockClient delegate = new RecordingLockClient();
        try (WatchdogLockClient watchdog = new WatchdogLockClient(delegate, 30_000, null)) {
            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Integer> results = new CopyOnWriteArrayList<>();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    results.add(watchdog.tryLock("job") ? 1 : 0);
                    return null;
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown(); // 同时放行制造最大竞争窗
            awaitQuiet(500);

            assertThat(results).containsOnly(1); // 假锁无限重入——全部成功
            assertThat(watchdog.activeWatchCount())
                    .as("并发首抢后看门狗必须恰一注册").isEqualTo(1);

            for (int i = 0; i < threads; i++) {
                watchdog.unlock("job");
            }
            assertThat(watchdog.activeWatchCount()).isEqualTo(0);
        }
    }

    /**
     * 续期失败（锁被他方抢占）：停狗 + 回调 LockLostListener 恰一次。
     */
    @Test
    void 续期失败_停狗且回调() throws Exception {
        RecordingLockClient delegate = new RecordingLockClient();
        AtomicReference<String> lost = new AtomicReference<>();
        // TTL 3000 → 续期间隔 1000ms，1.5s 内必见一次续期失败
        try (WatchdogLockClient watchdog = new WatchdogLockClient(delegate, 3_000, lost::set)) {
            assertThat(watchdog.tryLock("job")).isTrue();
            delegate.failAfterFirstAcquire = true; // 下一轮续期将失败（模拟抢占）

            Thread.sleep(RENEW_FLOOR_MS + 500);
            assertThat(lost.get()).as("续期失败必须回调 LockLostListener").isEqualTo("job");
            assertThat(watchdog.activeWatchCount()).isEqualTo(0);
        }
    }

    /** 简易等待（不抛 InterruptedException——用例性等待，打断即返回） */
    private static void awaitQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
