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
package com.xp.starsite.asynccmd.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.executor.ExecutorRegistry;
import com.xp.starsite.asynccmd.executor.ScheduleMode;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.store.InMemoryAsyncCommandStore;
import com.xp.starsite.lock.client.LockClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * LEADER 调度模式门控单测（memory 存储 + 可编程假锁 + 直驱 safeTick——
 * 门控在 safeTick 而非 tick，外部消费方入口是 start/tick，本类同包直驱门控层）。
 * 确定性策略：轮询间隔配 60s，内部调度线程在用例窗口内不触发，全部由手工 safeTick 驱动。
 *
 * @author muyi
 * @date 2026/10/25
 */
class AsyncCommandSchedulerLeaderTest {

    /** 假锁：可编程 tryLock 行为（true/false/抛异常）+ 调用记录 */
    static class FakeLock implements LockClient {

        /** tryLock 返回值（抛异常模式忽略） */
        final boolean grant;

        /** tryLock 抛异常模式（模拟锁存储故障） */
        final boolean throwOnTryLock;

        /** unlock 调用计数 */
        final AtomicInteger unlocks = new AtomicInteger();

        /** unlock 收到的锁名（stop 释放调度锁断言用） */
        final List<String> unlockedNames = new CopyOnWriteArrayList<>();

        /**
         * 构造假锁。
         *
         * @param grant           tryLock 固定返回值
         * @param throwOnTryLock  true 时 tryLock 抛异常（锁存储故障模拟）
         */
        FakeLock(boolean grant, boolean throwOnTryLock) {
            this.grant = grant;
            this.throwOnTryLock = throwOnTryLock;
        }

        @Override
        public boolean tryLock(String lockName) {
            if (throwOnTryLock) {
                throw new IllegalStateException("lock store down");
            }
            return grant;
        }

        @Override
        public void unlock(String lockName) {
            unlocks.incrementAndGet();
            unlockedNames.add(lockName);
        }
    }

    /** 记录执行的假执行器 */
    static class RecordingExecutor implements AsyncCommandExecutor {

        /** 已执行命令 id */
        final List<Long> executed = new CopyOnWriteArrayList<>();

        @Override
        public String type() {
            return "leader-task";
        }

        @Override
        public void execute(AsyncCommand cmd) {
            executed.add(cmd.getId());
        }
    }

    /** 被测调度器（每用例独立装配，@AfterEach 统一 stop） */
    private AsyncCommandScheduler scheduler;

    /** 当前用例的记录执行器 */
    private RecordingExecutor executor;

    /**
     * 装配 LEADER 模式调度器 + 已提交一条到期命令。
     *
     * @param lock 门控假锁
     * @return 已 start 的调度器（running=true，safeTick 可驱动）
     */
    private AsyncCommandScheduler leaderSchedulerWithPendingCommand(FakeLock lock) {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig config = new AsyncCommandConfig();
        config.setOwner("leader-test");
        config.setPollIntervalMs(60_000); // 内部调度线程窗口内不触发，手工 safeTick 驱动
        config.setBackoffBaseSeconds(0);
        config.setDefaultMaxRetry(1);
        executor = new RecordingExecutor();
        AsyncCommandClient client = new AsyncCommandClient(store, config);
        scheduler = new AsyncCommandScheduler(store, client, config,
                new ExecutorRegistry(() -> List.of(executor)));
        scheduler.setScheduleMode(ScheduleMode.LEADER);
        scheduler.setScheduleLock(lock);
        scheduler.start();
        client.submit("leader-task", "k1", "{}");
        return scheduler;
    }

    /** 统一停机（释放调度锁 + 关线程池），不残留后台线程 */
    @AfterEach
    void tearDown() {
        if (scheduler != null && scheduler.isStarted()) {
            scheduler.stop();
        }
    }

    /** 非 leader 实例（他方持锁）：safeTick 直接跳过，命令不认领不执行 */
    @Test
    void 非leader实例_跳过本轮调度() {
        leaderSchedulerWithPendingCommand(new FakeLock(false, false));

        scheduler.safeTick();

        assertThat(executor.executed).isEmpty(); // 门控生效：未持锁不进入 tick
    }

    /** leader 实例：持锁调度执行命令，stop 时释放调度锁（TTL 内不让位也不霸桩） */
    @Test
    void leader实例_持锁调度_停机释放锁() {
        FakeLock lock = new FakeLock(true, false);
        leaderSchedulerWithPendingCommand(lock);

        scheduler.safeTick();
        assertThat(executor.executed).hasSize(1);

        scheduler.stop();
        assertThat(lock.unlockedNames).containsExactly("async-cmd-scheduler"); // 停机让位
    }

    /** 锁存储故障 fail-open：tryLock 抛异常不外逃（逃出会取消调度线程永久停摆），本轮照常调度 */
    @Test
    void 锁存储故障_failOpen继续调度() {
        leaderSchedulerWithPendingCommand(new FakeLock(false, true));

        scheduler.safeTick(); // 修复前：异常逃出 safeTick → scheduleWithFixedDelay 被静默取消

        assertThat(executor.executed).hasSize(1); // fail-open：锁故障不停摆调度
    }
}
