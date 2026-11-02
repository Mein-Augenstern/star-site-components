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
package com.xp.starsite.schedule.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.schedule.api.MisfirePolicy;
import com.xp.starsite.schedule.api.RunStatus;
import com.xp.starsite.schedule.clock.TimeSource;
import com.xp.starsite.schedule.store.MemoryRunLogStore;
import com.xp.starsite.schedule.trigger.DailyAtTrigger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 恰一执行器测试：跨实例互斥（FakeLock 模拟集群争抢）、SKIPPED_LOCKED 留痕、
 * FAILED 异常吞噬、fail-open 放行、guard=false 本地模式。
 *
 * @author muyi
 * @date 2026/11/02
 */
class ExactlyOnceRunnerTest {

    /** 集群锁模拟：多实例共享一个持有集（ConcurrentHashMap.add 原子=唯一键语义）；attempt 计数供并发测试同步 */
    static final class FakeCluster {
        final Set<String> held = ConcurrentHashMap.newKeySet();
        final AtomicInteger attempts = new AtomicInteger();

        LockClient client() {
            return new LockClient() {
                @Override
                public boolean tryLock(String lockName) {
                    attempts.incrementAndGet();
                    return held.add(lockName);
                }

                @Override
                public void unlock(String lockName) {
                    held.remove(lockName);
                }
            };
        }
    }

    /** tryLock 抛异常的锁（fail-open 场景） */
    static LockClient brokenLock() {
        return new LockClient() {
            @Override
            public boolean tryLock(String lockName) {
                throw new IllegalStateException("lock store down");
            }

            @Override
            public void unlock(String lockName) {
                throw new IllegalStateException("lock store down");
            }
        };
    }

    private static ScheduledTaskDef def(boolean guard, Runnable body) {
        return new ScheduledTaskDef("t1", new DailyAtTrigger(8, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.SKIP, guard, body);
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 11, 2, 8, 0, 0);
    }

    @Test
    void skipsAndRecordsWhenOtherInstanceHoldsLock() {
        FakeCluster cluster = new FakeCluster();
        MemoryRunLogStore store = new MemoryRunLogStore();
        ExactlyOnceRunner runner = new ExactlyOnceRunner(cluster.client(), store, TimeSource.system());

        assertThat(cluster.client().tryLock("sch:t1")).isTrue();  // 他方实例先持锁

        AtomicInteger executed = new AtomicInteger();
        boolean ran = runner.run(def(true, executed::incrementAndGet), now());

        assertThat(ran).isFalse();  // 让位非错误
        assertThat(executed.get()).isZero();
        List<com.xp.starsite.schedule.api.RunLog> logs = store.recentByTask("t1", 10);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo(RunStatus.SKIPPED_LOCKED);
        assertThat(logs.get(0).getLockName()).isEqualTo("sch:t1");
    }

    @Test
    void executesRecordsDoneAndReleasesLock() {
        FakeCluster cluster = new FakeCluster();
        MemoryRunLogStore store = new MemoryRunLogStore();
        ExactlyOnceRunner runner = new ExactlyOnceRunner(cluster.client(), store, TimeSource.system());

        boolean ran = runner.run(def(true, () -> { }), now());

        assertThat(ran).isTrue();
        assertThat(cluster.held).isEmpty();  // 释放干净（TTL 不用兜底）
        var log = store.recentByTask("t1", 10).get(0);
        assertThat(log.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(log.getDurationMs()).isNotNull();
        assertThat(log.getHost()).contains(":");  // hostname:pid 形态
        assertThat(log.getTriggerTime()).isEqualTo(now());
    }

    @Test
    void failureIsSwallowedAndRecorded() {
        FakeCluster cluster = new FakeCluster();
        MemoryRunLogStore store = new MemoryRunLogStore();
        ExactlyOnceRunner runner = new ExactlyOnceRunner(cluster.client(), store, TimeSource.system());

        boolean ran = runner.run(def(true, () -> {
            throw new IllegalStateException("boom");
        }), now());

        assertThat(ran).isTrue();  // 执行了（失败也是执行）
        assertThat(cluster.held).isEmpty();  // 失败也释放
        var log = store.recentByTask("t1", 10).get(0);
        assertThat(log.getStatus()).isEqualTo(RunStatus.FAILED);
        var detail = store.findById(log.getId()).orElseThrow();
        assertThat(detail.getError()).contains("IllegalStateException").contains("boom");
        assertThat(detail.getFinishedAt()).isNotNull();
    }

    @Test
    void failOpenRunsWhenLockStoreThrows() {
        // fail-open 契约：锁存储异常放行执行（任务体必须幂等的理由）
        MemoryRunLogStore store = new MemoryRunLogStore();
        ExactlyOnceRunner runner = new ExactlyOnceRunner(brokenLock(), store, TimeSource.system());

        AtomicInteger executed = new AtomicInteger();
        boolean ran = runner.run(def(true, executed::incrementAndGet), now());

        assertThat(ran).isTrue();
        assertThat(executed.get()).isEqualTo(1);
        assertThat(store.recentByTask("t1", 10).get(0).getStatus()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void localModeSkipsLockEntirely() {
        MemoryRunLogStore store = new MemoryRunLogStore();
        // lockClient=null + guard=false：本地模式每实例都跑
        ExactlyOnceRunner runner = new ExactlyOnceRunner(null, store, TimeSource.system());

        AtomicInteger executed = new AtomicInteger();
        assertThat(runner.run(def(false, executed::incrementAndGet), now())).isTrue();
        assertThat(executed.get()).isEqualTo(1);
    }

    @Test
    void concurrentRunsExactlyOnce() throws Exception {
        // AC：同刻两实例并发触发 → 恰一执行 + 一条 SKIPPED_LOCKED
        FakeCluster cluster = new FakeCluster();
        MemoryRunLogStore storeA = new MemoryRunLogStore();
        MemoryRunLogStore storeB = new MemoryRunLogStore();
        ExactlyOnceRunner a = new ExactlyOnceRunner(cluster.client(), storeA, TimeSource.system());
        ExactlyOnceRunner b = new ExactlyOnceRunner(cluster.client(), storeB, TimeSource.system());

        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (ExactlyOnceRunner r : List.of(a, b)) {
            new Thread(() -> {
                try {
                    start.await();
                    r.run(def(true, () -> {
                        // 阻塞到两实例都已尝试争锁——确保败者已拿到 false 后胜者才释放
                        awaitAttempts(cluster, 2);
                        executions.incrementAndGet();
                    }), now());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(cluster.attempts.get()).isEqualTo(2);
        assertThat(executions.get()).isEqualTo(1);  // 恰一
        long skipped = storeA.recentByTask("t1", 10).stream()
                .filter(l -> l.getStatus() == RunStatus.SKIPPED_LOCKED).count()
                + storeB.recentByTask("t1", 10).stream()
                .filter(l -> l.getStatus() == RunStatus.SKIPPED_LOCKED).count();
        assertThat(skipped).isEqualTo(1);  // 让位侧留痕
        assertThat(cluster.held).isEmpty();  // 都释放了
    }

    /** 自旋等待争锁尝试次数达标（带超时上限，防死等） */
    private static void awaitAttempts(FakeCluster cluster, int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (cluster.attempts.get() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
