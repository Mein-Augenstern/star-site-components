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

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.executor.ExecutorRegistry;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.scheduler.AsyncCommandScheduler;
import com.xp.starsite.asynccmd.store.InMemoryAsyncCommandStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 调度流程单测（memory 存储 + 直接驱动 tick）：成功/重试/死信/幂等/延迟/双实例认领互斥/悬挂回收。
 * 确定性策略：不走内部调度线程，用例内手工调用 tick() 逐轮驱动；退避配 0 秒使重试立即到期，无需真实等待。
 *
 * @author muyi
 * @date 2026/10/25
 */
class AsyncCommandSchedulerTest {

    /**
     * 测试设施：可编程成败的假执行器——按执行顺序记录命令 id，前 failFirstN 次抛异常模拟失败，之后成功。
     */
    static class FakeExecutor implements AsyncCommandExecutor {
        /** 路由键（type() 返回值） */
        final String typeName;
        /** 已执行命令 id（按执行顺序记录）；CopyOnWriteArrayList：写少读多，且可能被调度线程与测试线程并发读 */
        final List<Long> executed = new CopyOnWriteArrayList<>();
        /** 前 N 次执行抛异常模拟失败（0 = 每次都成功） */
        int failFirstN;

        /**
         * 构造假执行器。
         *
         * @param typeName    路由键
         * @param failFirstN 前 N 次执行失败（0 = 永不失败；Integer.MAX_VALUE = 永远失败）
         */
        FakeExecutor(String typeName, int failFirstN) {
            this.typeName = typeName;
            this.failFirstN = failFirstN;
        }

        /**
         * 返回构造时指定的路由键。
         *
         * @return 路由键
         */
        @Override
        public String type() {
            return typeName;
        }

        /**
         * 模拟执行：失败额度未用完时抛 IllegalStateException("boom#n")（失败信息供死信 lastError 断言），
         * 否则记录 id 后正常返回（成功）。
         *
         * @param cmd 被执行的命令
         */
        @Override
        public void execute(AsyncCommand cmd) {
            if (executed.size() < failFirstN) {
                executed.add(cmd.getId());
                throw new IllegalStateException("boom#" + executed.size());
            }
            executed.add(cmd.getId());
        }
    }

    /**
     * 测试统一参数：maxRetry=2（三步即可走完死信路径）；退避 0 秒使重试命令立即到期，tick 可连续驱动。
     *
     * @return 测试用调度参数
     */
    private static AsyncCommandConfig config() {
        AsyncCommandConfig c = new AsyncCommandConfig();
        c.setDefaultMaxRetry(2);
        // 退避 0 秒：重试命令立即到期，tick 可连续驱动无需真实等待
        c.setBackoffBaseSeconds(0);
        c.setBackoffMaxSeconds(0);
        c.setBatchSize(10);
        c.setStaleAfterSeconds(300);
        c.setOwner("test");
        return c;
    }

    /**
     * 验证成功路径：提交后单轮 tick 即路由执行，命令落 SUCCESS。
     */
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

    /**
     * 验证失败重试：执行失败回 PENDING 指数退避，重试后成功，retryCount 终值与执行次数正确。
     */
    @Test
    void 失败重试_指数退避后成功() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        // 前 2 次执行失败，第 3 次成功
        FakeExecutor exec = new FakeExecutor("flaky", 2);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("flaky", "k1", "{}");
        // 第 1 次失败 → retry#1
        scheduler.tick();
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
        // 第 2 次失败 → retry#2
        scheduler.tick();
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
        // 第 3 次成功
        scheduler.tick();
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
        assertThat(exec.executed).hasSize(3);
        // 重试计数终值 = 2
        assertThat(store.selectRecent(1).get(0).getRetryCount()).isEqualTo(2);
    }

    /**
     * 验证重试超限：达 maxRetry 后进 DEAD 死信，DEAD 不再被认领执行。
     */
    @Test
    void 超限重试_进DEAD死信() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        // maxRetry=2：两次重试用完即进 DEAD
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("always-fail", Integer.MAX_VALUE);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        client.submit("always-fail", "k1", "{}");
        // 失败 → retry#1
        scheduler.tick();
        // 失败 → retry#2
        scheduler.tick();
        // 失败 → 已达 maxRetry → DEAD
        scheduler.tick();
        assertThat(store.countByStatus(AsyncCommand.DEAD)).isEqualTo(1);
        // 共执行 3 次（1 首次 + 2 重试）
        assertThat(exec.executed).hasSize(3);
        // DEAD 不再被认领
        scheduler.tick();
        assertThat(exec.executed).hasSize(3);
    }

    /**
     * 验证死信可排查性：进 DEAD 时 lastError 保留最后一次失败原因。
     */
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

    /**
     * 验证幂等提交：同 type+bizKey 存在未完成命令时二次提交跳过（返回 null）；落终态后可再次提交。
     */
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

    /**
     * 验证延迟队列语义：nextRunAt 未到期不认领不执行；到期即认领执行。
     */
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

    /**
     * 验证多实例认领互斥：两调度器共享存储，先到的 RUNNING 不会被后到实例重认领，
     * 认领集合不相交且全部落 SUCCESS。（满批追赶引入后，单 tick 会连认多轮——
     * 互斥验证改为 B 先不满批认领、A 后到只拿剩余，语义仍是"同一命令只被一个实例执行"）
     */
    @Test
    void 双实例认领互斥_同一命令只被一个实例执行() {
        // 两实例共享同一存储（模拟共表部署）
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
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

        // B 先到：2 条不满批（单轮即停），A 后到只能拿新提交的剩余 2 条
        clientA.submit("race", "k0", "{}");
        clientA.submit("race", "k1", "{}");
        schB.tick();
        clientA.submit("race", "k2", "{}");
        clientA.submit("race", "k3", "{}");
        schA.tick();
        assertThat(execA.executed).hasSize(2);
        assertThat(execB.executed).hasSize(2);
        // 两实例认领的命令 id 完全不相交
        assertThat(execA.executed).doesNotContainAnyElementsOf(execB.executed);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(4);
    }

    /**
     * OBS-101 回归：满批追赶——batch=2、积压 5 条时单 tick 连续认领三轮（2+2+1）清空积压，
     * 而非旧语义的一 tick 只认一批留 3 条等下个轮询间隔。
     */
    @Test
    void 满批追赶_单tick连续认领清积压() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        c.setBatchSize(2);
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("burst", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        for (int i = 0; i < 5; i++) {
            client.submit("burst", "k" + i, "{}");
        }
        scheduler.tick();
        assertThat(exec.executed).as("单 tick 应连续认领三轮清空 5 条积压").hasSize(5);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(5);
    }

    /**
     * OBS-101 回归：追赶有界——积压远超单 tick 上限时，一轮 tick 至多认领
     * {@code MAX_DRAIN_ROUNDS × batchSize} 条，剩余留给后续 tick（防慢执行器场景打爆池队列）。
     */
    @Test
    void 满批追赶_轮数上限有界() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        c.setBatchSize(2);
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        FakeExecutor exec = new FakeExecutor("flood", 0);
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of(exec)));

        for (int i = 0; i < 50; i++) {
            client.submit("flood", "k" + i, "{}");
        }
        scheduler.tick();
        // 上限轮数 8（包级常量，镜像断言；轮数调整时两处同步）
        int maxRounds = 8;
        assertThat(exec.executed).as("单 tick 追加上限 = 8 轮 × 2 条").hasSize(maxRounds * 2);
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(50 - maxRounds * 2);
        // 下一轮 tick 继续消化（追赶不丢失剩余积压）
        scheduler.tick();
        assertThat(exec.executed).hasSize(maxRounds * 4);
    }

    /**
     * 验证悬挂回收：进程死亡残留的 RUNNING 命令，在恢复实例的下一轮先被回收回 PENDING 再重跑成功。
     */
    @Test
    void 悬挂RUNNING_超时回收后重跑() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        // 阈值 0：立即视为悬挂
        c.setStaleAfterSeconds(0);
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

    /**
     * 验证无执行器路由的命令按失败重试处理（回 PENDING、记 lastError），而不是被无声跳过。
     */
    @Test
    void 无注册执行器_按失败重试() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommandConfig c = config();
        AsyncCommandClient client = new AsyncCommandClient(store, c);
        // 注册表为空：任何类型都无执行器
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, c,
                new ExecutorRegistry(() -> List.of()));

        client.submit("no-executor", "k1", "{}");
        scheduler.tick();
        AsyncCommand cmd = store.selectRecent(1).get(0);
        assertThat(cmd.getStatus()).isEqualTo(AsyncCommand.PENDING);
        assertThat(cmd.getLastError()).contains("无注册执行器");
    }

    /**
     * 验证手工生命周期：start 幂等（重复调用无副作用）；stop 后 tick 静默（不认领不执行）。
     */
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
        // 重复 start 应幂等，无异常无重复任务
        scheduler.start();
        scheduler.stop();
        assertThat(scheduler.isStarted()).isFalse();

        client.submit("lifecycle", "k1", "{}");
        // 停止后 tick 静默：不认领不执行
        scheduler.tick();
        assertThat(exec.executed).isEmpty();
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
    }
}
