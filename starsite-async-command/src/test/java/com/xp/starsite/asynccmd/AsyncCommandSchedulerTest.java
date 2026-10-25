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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 调度流程单测（memory 存储 + 直接驱动 tick）：成功/重试/死信/幂等/延迟/双实例认领互斥/悬挂回收。
 *
 * @author muyi
 * @since 2026-10
 */
class AsyncCommandSchedulerTest {

    /** 记录调用并可编程决定成败的假执行器 */
    static class FakeExecutor implements AsyncCommandExecutor {
        final String typeName;
        final List<Long> executed = new CopyOnWriteArrayList<>();
        int failFirstN;

        FakeExecutor(String typeName, int failFirstN) {
            this.typeName = typeName;
            this.failFirstN = failFirstN;
        }

        @Override
        public String type() {
            return typeName;
        }

        @Override
        public void execute(AsyncCommand cmd) {
            if (executed.size() < failFirstN) {
                executed.add(cmd.getId());
                throw new IllegalStateException("boom#" + executed.size());
            }
            executed.add(cmd.getId());
        }
    }

    private static AsyncCommandConfig config() {
        AsyncCommandConfig c = new AsyncCommandConfig();
        c.setDefaultMaxRetry(2);
        c.setBackoffBaseSeconds(0);   // 测试立即到期
        c.setBackoffMaxSeconds(0);
        c.setBatchSize(10);
        c.setStaleAfterSeconds(300);
        c.setOwner("test");
        return c;
    }

    @Test
    void 成功路径_执行一次落SUCCESS() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("sync", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("sync", "k1", "{}");
        scheduler.tick();

        assertThat(exec.executed).hasSize(1);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
    }

    @Test
    void 失败重试_指数退避后成功() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("flaky", 2); // 前 2 次失败
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("flaky", "k1", "{}");
        scheduler.tick(); // 第 1 次失败 → retry#1
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
        scheduler.tick(); // 第 2 次失败 → retry#2
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
        scheduler.tick(); // 第 3 次成功
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
        assertThat(exec.executed).hasSize(3);
        // 重试计数终值 = 2
        assertThat(store.selectRecent(1).get(0).getRetryCount()).isEqualTo(2);
    }

    @Test
    void 超限重试_进DEAD死信() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config(); // maxRetry=2
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("always-fail", Integer.MAX_VALUE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("always-fail", "k1", "{}");
        scheduler.tick(); // 失败 → retry#1
        scheduler.tick(); // 失败 → retry#2
        scheduler.tick(); // 失败 → 已达 maxRetry → DEAD
        assertThat(store.countByStatus(AsyncCommand.DEAD)).isEqualTo(1);
        assertThat(exec.executed).hasSize(3); // 共执行 3 次（1 首次 + 2 重试）
        scheduler.tick(); // DEAD 不再被认领
        assertThat(exec.executed).hasSize(3);
    }

    @Test
    void 死信带最后错误信息() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("err", Integer.MAX_VALUE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("err", "k1", "{}");
        for (int i = 0; i < 3; i++) scheduler.tick();
        assertThat(store.selectRecent(1).get(0).getLastError()).contains("boom");
    }

    @Test
    void 幂等提交_未完成时跳过() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("idem", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submitIdempotent("idem", "order-1", "{}");
        AsyncCommand second = client.submitIdempotent("idem", "order-1", "{}");
        assertThat(second).as("未完成期间二次提交应跳过").isNull();

        scheduler.tick();
        AsyncCommand third = client.submitIdempotent("idem", "order-1", "{}");
        assertThat(third).as("完成后可再次提交").isNotNull();
    }

    @Test
    void 延迟命令_未到期不认领_到期执行() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("delayed", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submitAt("delayed", "k1", "{}", LocalDateTime.now().plusHours(1));
        scheduler.tick();
        assertThat(exec.executed).as("未到期不应执行").isEmpty();
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);

        client.submitAt("delayed", "k2", "{}", LocalDateTime.now().minusSeconds(1));
        scheduler.tick();
        assertThat(exec.executed).as("到期即执行").hasSize(1);
    }

    @Test
    void 双实例认领互斥_同一命令只被一个实例执行() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore(); // 两实例共享同一存储
        AsyncCommandConfig c = config();
        c.setBatchSize(2);
        AsyncCommandClient clientA = new AsyncCommandClient(store, c);
        AsyncCommandClient clientB = new AsyncCommandClient(store, c);
        FakeExecutor execA = new FakeExecutor("race", 0);
        FakeExecutor execB = new FakeExecutor("race", 0);
        AsyncCommandScheduler schA = new AsyncCommandScheduler(store, clientA, c,
                new ExecutorRegistry(() -> List.of(execA)));
        AsyncCommandScheduler schB = new AsyncCommandScheduler(store, clientB, c,
                new ExecutorRegistry(() -> List.of(execB)));

        for (int i = 0; i < 4; i++) {
            clientA.submit("race", "k" + i, "{}");
        }
        schA.tick(); // 批量 2
        schB.tick(); // 批量 2
        assertThat(execA.executed).hasSize(2);
        assertThat(execB.executed).hasSize(2);
        // 两实例认领的命令 id 完全不相交
        assertThat(execA.executed).doesNotContainAnyElementsOf(execB.executed);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(4);
    }

    @Test
    void 悬挂RUNNING_超时回收后重跑() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        c.setStaleAfterSeconds(0); // 立即视为悬挂
        AsyncCommandClient client = new AsyncCommandClient(store, c);

        // 模拟进程死亡：认领后滞留 RUNNING（死亡进程没有机会回写终态）
        client.submit("hang", "k1", "{}");
        store.claim("dead-process-token", 5, LocalDateTime.now());
        assertThat(store.countByStatus(AsyncCommand.RUNNING)).isEqualTo(1);

        // 恢复实例：下一轮先回收悬挂（回 PENDING）再正常执行
        FakeExecutor recovered = new FakeExecutor("hang", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(recovered)));
        scheduler.tick();
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
        assertThat(recovered.executed).hasSize(1);
    }

    @Test
    void 无注册执行器_按失败重试() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of())); // 无执行器

        client.submit("no-executor", "k1", "{}");
        scheduler.tick();
        AsyncCommand cmd = store.selectRecent(1).get(0);
        assertThat(cmd.getStatus()).isEqualTo(AsyncCommand.PENDING);
        assertThat(cmd.getLastError()).contains("无注册执行器");
    }

    @Test
    void 手工生命周期_start幂等_stop后tick静默() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("lifecycle", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        scheduler.start();
        assertThat(scheduler.isStarted()).isTrue();
        scheduler.start(); // 幂等
        scheduler.stop();
        assertThat(scheduler.isStarted()).isFalse();

        client.submit("lifecycle", "k1", "{}");
        scheduler.tick(); // 停止后 tick 静默：不认领不执行
        assertThat(exec.executed).isEmpty();
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
    }
}
