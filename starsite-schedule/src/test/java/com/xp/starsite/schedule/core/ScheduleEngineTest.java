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
import com.xp.starsite.schedule.api.TaskStatus;
import com.xp.starsite.schedule.clock.TimeSource;
import com.xp.starsite.schedule.store.MemoryRunLogStore;
import com.xp.starsite.schedule.store.RunLogStore;
import com.xp.starsite.schedule.trigger.DailyAtTrigger;
import com.xp.starsite.schedule.trigger.FixedDelayTrigger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 调度引擎测试：假时钟驱动真实 tick 循环——到点触发 / 恰一去重 / misfire 补跑 /
 * 间隔型重排 / 悬挂回收 / triggerNow / 快照。
 *
 * @author muyi
 * @date 2026/11/02
 */
class ScheduleEngineTest {

    /** 假时钟：测试线程推进，引擎线程读（volatile 写读足够——秒级推进远慢于 tick） */
    static final class MutableClock implements TimeSource {
        volatile LocalDateTime now = LocalDateTime.of(2026, 11, 2, 12, 0, 0);

        void advanceTo(LocalDateTime t) {
            now = t;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public LocalDateTime now() {
            return now;
        }
    }

    /** 单实例无争抢锁（恰一去重由引擎 inFlight 保证；跨实例互斥在 ExactlyOnceRunnerTest 覆盖） */
    private static LockClient permissiveLock() {
        Set<String> held = ConcurrentHashMap.newKeySet();
        return new LockClient() {
            @Override
            public boolean tryLock(String lockName) {
                return held.add(lockName);
            }

            @Override
            public void unlock(String lockName) {
                held.remove(lockName);
            }
        };
    }

    MutableClock clock = new MutableClock();
    ScheduleTaskRegistry registry = new ScheduleTaskRegistry();
    RunLogStore store = new MemoryRunLogStore();
    ExactlyOnceRunner runner = new ExactlyOnceRunner(permissiveLock(), store, clock);
    ScheduleEngine engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
    }

    private ScheduleEngine startEngine() {
        engine = new ScheduleEngine(registry, runner, store, clock, 10, 2, Duration.ofHours(1));
        engine.start();
        return engine;
    }

    private static void await(AtomicInteger counter, int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (counter.get() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void firesAtScheduledTimeExactlyOncePerTrigger() {
        AtomicInteger executions = new AtomicInteger();
        registry.register(new ScheduledTaskDef("daily", new DailyAtTrigger(20, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.SKIP, true, executions::incrementAndGet));
        startEngine();

        assertThat(engine.status("daily").orElseThrow().getNextRun())
                .contains(LocalDateTime.of(2026, 11, 2, 20, 0, 0));

        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 20, 0, 0));
        await(executions, 1);
        assertThat(executions.get()).isEqualTo(1);

        // 时钟停在触发点不动（多个 tick 周期）——同一触发点只执行一次
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(executions.get()).isEqualTo(1);

        // 下次触发推进到明天 20:00
        assertThat(engine.status("daily").orElseThrow().getNextRun())
                .contains(LocalDateTime.of(2026, 11, 3, 20, 0, 0));
        clock.advanceTo(LocalDateTime.of(2026, 11, 3, 20, 0, 0));
        await(executions, 2);
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    void fixedDelayReschedulesAfterCompletion() {
        // 间隔型：完成后起算（间隔 60s）；执行慢不堆积
        AtomicInteger executions = new AtomicInteger();
        registry.register(new ScheduledTaskDef("poll", new FixedDelayTrigger(60),
                MisfirePolicy.SKIP, true, executions::incrementAndGet));
        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 12, 0, 0));
        startEngine();

        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 12, 1, 0));  // 60s 后
        await(executions, 1);
        assertThat(executions.get()).isEqualTo(1);

        // 未到下一间隔（完成时刻+60s）不触发
        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 12, 1, 30));
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(executions.get()).isEqualTo(1);

        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 12, 2, 2));
        await(executions, 2);
    }

    @Test
    void misfireRunOnceCatchesUpExactlyOne() {
        // 昨晚 20:00 成功过；今晨实例全停错过今午 12:00 触发点 → 启动即补跑一次
        LocalDateTime yesterdayDone = LocalDateTime.of(2026, 11, 1, 20, 0, 0);
        store.start("catchup", "sch:catchup", yesterdayDone, yesterdayDone, "h:p");
        store.finish(store.recentByTask("catchup", 1).get(0).getId(), RunStatus.DONE, 10L, null);

        AtomicInteger executions = new AtomicInteger();
        registry.register(new ScheduledTaskDef("catchup",
                new DailyAtTrigger(12, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.RUN_ONCE, true, executions::incrementAndGet));
        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 14, 0, 0));  // 已过今日 12:00
        startEngine();

        await(executions, 1);
        assertThat(executions.get()).isEqualTo(1);  // 补跑恰一次

        // 补跑的流水记缺口时刻（12:00）而非启动时刻（14:00）——留痕诚实
        // （await 到执行完成后状态已落终态 DONE）
        var log = store.recentByTask("catchup", 5).get(0);
        assertThat(log.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(log.getTriggerTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 0, 0));

        // 不回放第二遍
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void misfireSkipDoesNotCatchUp() {
        LocalDateTime yesterdayDone = LocalDateTime.of(2026, 11, 1, 20, 0, 0);
        store.start("skipper", "sch:skipper", yesterdayDone, yesterdayDone, "h:p");
        store.finish(store.recentByTask("skipper", 1).get(0).getId(), RunStatus.DONE, 10L, null);

        AtomicInteger executions = new AtomicInteger();
        registry.register(new ScheduledTaskDef("skipper",
                new DailyAtTrigger(12, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.SKIP, true, executions::incrementAndGet));
        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 14, 0, 0));
        startEngine();

        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(executions.get()).isZero();  // SKIP：错过就错过
    }

    @Test
    void neverRunTaskDoesNotBackfillHistory() {
        // 从未成功过的任务不回溯全历史（首跑从下一计划时刻起）
        AtomicInteger executions = new AtomicInteger();
        registry.register(new ScheduledTaskDef("fresh",
                new DailyAtTrigger(12, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.RUN_ONCE, true, executions::incrementAndGet));
        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 14, 0, 0));
        startEngine();

        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(executions.get()).isZero();
        assertThat(engine.status("fresh").orElseThrow().getNextRun())
                .contains(LocalDateTime.of(2026, 11, 3, 12, 0, 0));
    }

    @Test
    void staleRunningMarkedOnStartup() {
        // 上实例崩溃遗留 RUNNING（昨天开始，超 1h 阈值）→ 启动巡检标 STALE
        LocalDateTime yesterday = LocalDateTime.of(2026, 11, 1, 8, 0, 0);
        store.start("dead", "sch:dead", yesterday, yesterday, "h:p");
        clock.advanceTo(LocalDateTime.of(2026, 11, 2, 12, 0, 0));
        startEngine();

        assertThat(store.recentByTask("dead", 1).get(0).getStatus()).isEqualTo(RunStatus.STALE);
    }

    @Test
    void triggerNowUsesSameLockAndRejectsWhileRunning() throws Exception {
        AtomicReference<Thread> bodyThread = new AtomicReference<>();
        AtomicInteger started = new AtomicInteger();
        java.util.concurrent.CountDownLatch bodyStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseBody = new java.util.concurrent.CountDownLatch(1);
        registry.register(new ScheduledTaskDef("manual",
                new DailyAtTrigger(3, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.SKIP, true, () -> {
                    started.incrementAndGet();
                    bodyThread.set(Thread.currentThread());
                    bodyStarted.countDown();
                    try {
                        releaseBody.await();  // 挂住任务体模拟长任务
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        startEngine();

        assertThat(engine.triggerNow("manual")).isTrue();
        assertThat(bodyStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 执行中再次手动触发：拒绝（同任务本实例不并发）
        assertThat(engine.triggerNow("manual")).isFalse();
        // 不存在的任务
        assertThat(engine.triggerNow("ghost")).isFalse();

        releaseBody.countDown();
        await(started, 1);
        assertThat(started.get()).isEqualTo(1);
    }

    @Test
    void tasksSnapshotAndDoubleStart() {
        registry.register(new ScheduledTaskDef("snap",
                new DailyAtTrigger(20, 0, ZoneId.of("Asia/Shanghai")), MisfirePolicy.SKIP, true, () -> { }));
        startEngine();
        engine.start();  // 重复启动忽略

        TaskStatus status = engine.status("snap").orElseThrow();
        assertThat(status.getName()).isEqualTo("snap");
        assertThat(status.getLockName()).isEqualTo("sch:snap");
        assertThat(status.getTrigger()).contains("dailyAt 20:00");
        assertThat(status.isGuarded()).isTrue();
        assertThat(status.getNextRun()).isPresent();
        assertThat(engine.tasks()).hasSize(1);
        engine.stop();
        assertThat(engine.isStarted()).isFalse();
    }
}
