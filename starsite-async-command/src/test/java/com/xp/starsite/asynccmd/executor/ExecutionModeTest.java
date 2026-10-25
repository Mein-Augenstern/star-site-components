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
package com.xp.starsite.asynccmd.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.scheduler.AsyncCommandScheduler;
import com.xp.starsite.asynccmd.store.InMemoryAsyncCommandStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * 执行模式与池隔离端到端测试：SCHEDULED 内联 / ASYNC_POOL 池化（隔离池与共享池）/
 * IMMEDIATE 提交即派发（不等 tick）、延迟命令降级、已认领命令不重复执行。
 *
 * @author muyi
 * @date 2026/10/25
 */
class ExecutionModeTest {

    /** 记录执行线程名的执行器基类（区分调度线程内联 vs 池线程） */
    static class RecordingExecutor implements AsyncCommandExecutor {
        final String typeName;
        final ExecutionMode mode;
        final List<String> threads = new CopyOnWriteArrayList<>();

        RecordingExecutor(String typeName, ExecutionMode mode) {
            this.typeName = typeName;
            this.mode = mode;
        }

        @Override public String type() { return typeName; }

        @Override public ExecutionMode mode() { return mode; }

        @Override public void execute(AsyncCommand cmd) {
            threads.add(Thread.currentThread().getName());
        }
    }

    /** 组合池隔离能力的执行器（隔离池 bean 注入由实现类自持——模拟业务侧用法） */
    static class IsolatedExecutor extends RecordingExecutor implements PoolIsolatedExecutor {
        private final Executor pool;

        IsolatedExecutor(String typeName, ExecutionMode mode, Executor pool) {
            super(typeName, mode);
            this.pool = pool;
        }

        @Override public Executor pool() { return pool; }
    }

    /** 单线程命名隔离池（线程名即验证锚点） */
    private static Executor namedPool(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static AsyncCommandConfig config() {
        AsyncCommandConfig c = new AsyncCommandConfig();
        c.setDefaultMaxRetry(1);
        c.setBackoffBaseSeconds(0);
        c.setBackoffMaxSeconds(0);
        c.setBatchSize(10);
        c.setOwner("test");
        return c;
    }

    @Test
    void scheduled模式_调度线程内联执行() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingExecutor exec = new RecordingExecutor("inline", ExecutionMode.SCHEDULED);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("inline", "k1", "{}");
        scheduler.tick();
        assertThat(exec.threads).hasSize(1);
        // 内联：执行线程就是调用 tick 的当前线程
        assertThat(exec.threads.get(0)).isEqualTo(Thread.currentThread().getName());
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
    }

    @Test
    void asyncPool模式_未隔离用组件共享池执行() throws Exception {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingExecutor exec = new RecordingExecutor("pooled", ExecutionMode.ASYNC_POOL);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("pooled", "k1", "{}");
        scheduler.tick(); // 只认领分发，不内联
        await(() -> !exec.threads.isEmpty());
        // 共享池线程（starsite-async-executor-*），非调用 tick 的当前线程
        assertThat(exec.threads.get(0)).startsWith("starsite-async-executor-");
        await(() -> store.countByStatus(AsyncCommand.SUCCESS) == 1);
        scheduler.stop();
    }

    @Test
    void asyncPool模式_隔离池_命令跑在自定义池线程() throws Exception {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        IsolatedExecutor exec = new IsolatedExecutor("ai", ExecutionMode.ASYNC_POOL, namedPool("iso-pool-ai"));
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("ai", "k1", "{}");
        scheduler.tick();
        await(() -> !exec.threads.isEmpty());
        assertThat(exec.threads.get(0)).isEqualTo("iso-pool-ai");
        await(() -> store.countByStatus(AsyncCommand.SUCCESS) == 1);
        scheduler.stop();
    }

    @Test
    void immediate模式_提交即执行_不等tick() throws Exception {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingExecutor exec = new RecordingExecutor("now", ExecutionMode.IMMEDIATE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));
        // 装配层在生产环境回填钩子；测试手工等价接线
        client.setSubmitHook(scheduler::onSubmit);

        client.submit("now", "k1", "{}");
        // 全程未调 tick——IMMEDIATE 已在提交路径认领并派发
        await(() -> store.countByStatus(AsyncCommand.SUCCESS) == 1);
        assertThat(exec.threads).hasSize(1);
        assertThat(exec.threads.get(0)).startsWith("starsite-async-executor-");
        scheduler.stop();
    }

    @Test
    void immediate模式_延迟命令不即时派发_仍由调度接管() throws Exception {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingExecutor exec = new RecordingExecutor("now", ExecutionMode.IMMEDIATE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));
        client.setSubmitHook(scheduler::onSubmit);

        client.submitAt("now", "k1", "{}", java.time.LocalDateTime.now().plusHours(1)); // 未到期
        Thread.sleep(200); // 给即时派发留窗口
        assertThat(exec.threads).as("未到期命令不应被即时派发").isEmpty();
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
        scheduler.stop();
    }

    @Test
    void immediate模式_已被他方认领_不重复执行() throws Exception {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingExecutor exec = new RecordingExecutor("now", ExecutionMode.IMMEDIATE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));
        // 先提交并让"他方"抢先认领，再回填钩子——模拟认领竞争已发生后的重复回调
        AsyncCommand cmd = client.submit("now", "k1", "{}");
        assertThat(store.claimById(cmd.getId(), "other-instance")).isTrue();
        client.setSubmitHook(scheduler::onSubmit);

        int before = exec.threads.size();
        scheduler.onSubmit(cmd);
        assertThat(exec.threads).hasSize(before);
        assertThat(store.countByStatus(AsyncCommand.RUNNING)).isEqualTo(1); // 他方令牌持有中
        scheduler.stop();
    }

    /** 轮询等待条件成立（池任务异步落终态），上限 5s 防死等 */
    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(cond.getAsBoolean()).as("等待条件超时").isTrue();
    }
}
