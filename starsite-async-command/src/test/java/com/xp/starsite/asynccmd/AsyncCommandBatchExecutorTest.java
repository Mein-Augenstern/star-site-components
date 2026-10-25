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
package com.xp.starsite.asynccmd;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.store.InMemoryAsyncCommandStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 批量执行器调度语义测试：同轮同类型合并单次调用、批失败整批重试、批单混跑分组正确。
 *
 * @author muyi
 * @date 2026/10/25
 */
class AsyncCommandBatchExecutorTest {

    /** 记录每次批量调用入参的假批量执行器（可编程决定第几次调用失败） */
    static class RecordingBatchExecutor implements AsyncCommandBatchExecutor {
        final String typeName;
        final List<List<Long>> batches = new CopyOnWriteArrayList<>();
        int failFirstNCalls;

        RecordingBatchExecutor(String typeName, int failFirstNCalls) {
            this.typeName = typeName;
            this.failFirstNCalls = failFirstNCalls;
        }

        @Override
        public String type() {
            return typeName;
        }

        @Override
        public void executeBatch(List<AsyncCommand> cmds) {
            if (batches.size() < failFirstNCalls) {
                batches.add(idsOf(cmds));
                throw new IllegalStateException("batch-boom#" + batches.size());
            }
            batches.add(idsOf(cmds));
        }

        private static List<Long> idsOf(List<AsyncCommand> cmds) {
            return cmds.stream().map(AsyncCommand::getId).toList();
        }
    }

    /** 计数假指标：验证调度器/client 在批量路径下的打点行为 */
    static class CountingMetrics implements AsyncCommandMetrics {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void recordSubmitted(String cmdType) { events.add("submitted:" + cmdType); }
        @Override public void recordSuccess(String cmdType, java.time.Duration d) { events.add("success:" + cmdType); }
        @Override public void recordRetry(String cmdType) { events.add("retry:" + cmdType); }
        @Override public void recordDead(String cmdType) { events.add("dead:" + cmdType); }
    }

    private static AsyncCommandConfig config() {
        AsyncCommandConfig c = new AsyncCommandConfig();
        c.setDefaultMaxRetry(2);
        c.setBackoffBaseSeconds(0);
        c.setBackoffMaxSeconds(0);
        c.setBatchSize(10);
        c.setOwner("test");
        return c;
    }

    @Test
    void 同轮同类型命令_合并为一次批量调用() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        CountingMetrics metrics = new CountingMetrics();
        AsyncCommandClient client = new AsyncCommandClient(store, c, com.xp.starsite.asynccmd.TimeSource.system(), metrics);
        RecordingBatchExecutor batchExec = new RecordingBatchExecutor("notify", 0);
        AsyncCommandExecutor singleExec = new AsyncCommandExecutor() {
            @Override public String type() { return "audit"; }
            @Override public void execute(AsyncCommand cmd) { }
        };
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(batchExec, singleExec)),
                com.xp.starsite.asynccmd.TimeSource.system(), metrics);

        for (int i = 0; i < 3; i++) {
            client.submit("notify", "n" + i, "{}");
        }
        client.submit("audit", "a1", "{}");
        scheduler.tick();

        // 3 条 notify 合并为一次调用（组内 id 升序）；audit 走单条路径不进批
        assertThat(batchExec.batches).hasSize(1);
        assertThat(batchExec.batches.get(0)).hasSize(3);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(4);
        // 成功指标：批记 1 次 + 单条记 1 次
        assertThat(metrics.events).containsExactlyInAnyOrder(
                "submitted:notify", "submitted:notify", "submitted:notify", "submitted:audit",
                "success:notify", "success:audit");
    }

    @Test
    void 批量失败_整批进入重试_下轮整批成功() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingBatchExecutor batchExec = new RecordingBatchExecutor("push", 1); // 首次调用失败
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(batchExec)));

        client.submit("push", "k1", "{}");
        client.submit("push", "k2", "{}");
        scheduler.tick(); // 第 1 轮：整批失败 → 两条各自进入 retry#1
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(2);
        assertThat(store.selectRecent(2)).allMatch(cmd -> cmd.getRetryCount() == 1);

        scheduler.tick(); // 第 2 轮：整批成功
        assertThat(batchExec.batches).hasSize(2);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(2);
    }

    @Test
    void 批量持续失败_整批各自计数退避_超限各自进DEAD() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config(); // maxRetry=2
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        RecordingBatchExecutor batchExec = new RecordingBatchExecutor("bad", Integer.MAX_VALUE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(batchExec)));

        client.submit("bad", "k1", "{}");
        client.submit("bad", "k2", "{}");
        scheduler.tick(); // retry#1
        scheduler.tick(); // retry#2
        scheduler.tick(); // 超限 → 整批 DEAD
        assertThat(store.countByStatus(AsyncCommand.DEAD)).isEqualTo(2);
        assertThat(batchExec.batches).hasSize(3); // 首次 + 2 次重试 = 3 次批量调用
    }

    @Test
    void 组内保持id升序() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        List<List<Long>> seen = new ArrayList<>();
        RecordingBatchExecutor batchExec = new RecordingBatchExecutor("order", 0) {
            @Override public void executeBatch(List<AsyncCommand> cmds) {
                seen.add(cmds.stream().map(AsyncCommand::getId).toList());
                super.executeBatch(cmds);
            }
        };
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(batchExec)));

        for (int i = 0; i < 3; i++) {
            client.submit("order", "k" + i, "{}");
        }
        scheduler.tick();
        assertThat(seen.get(0)).isSorted(); // 认领返回 id 升序，分组保持原顺序
    }
}
