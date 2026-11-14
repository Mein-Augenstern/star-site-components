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
package com.xp.starsite.idempotent.core;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.idempotent.api.IdempotentAction;
import com.xp.starsite.idempotent.api.IdempotentResult;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.memory.MemoryIdempotentStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 幂等引擎语义矩阵（memory store + 假时钟——窗口/停滞判定确定性推进）：
 * 恰一执行 / 结果回放 / 失败释放键 / 键遗忘重执行 / 崩溃回收 /
 * 在飞等待（回放/目睹失败/超时冲突）/ 作用域隔离 / 代际守卫 / purge。
 *
 * @author muyi
 * @date 2026/11/12
 */
class IdempotentEngineTest {

    /** 假时钟（窗口推进的确定性前提） */
    private static final class FakeClock implements TimeSource {

        /** 当前时刻（volatile：等待线程与推进线程可见） */
        volatile LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0, 0);

        /** {@inheritDoc} */
        @Override
        public LocalDateTime now() {
            return now;
        }

        /** 推进秒（模拟时间流逝） */
        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }
    }

    /** 假时钟 */
    private FakeClock clock;

    /** 被测引擎（每用例重建） */
    private IdempotentEngine engine;

    /** 存储直访（构造在飞/崩溃场景） */
    private MemoryIdempotentStore store;

    /** 动作执行计数（回放断言的动作零执行依据） */
    private final AtomicInteger actionRuns = new AtomicInteger();

    /** 单线程执行池（等待用例的副线程） */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 每用例重建引擎（生产缺省参数同形：24h 窗口/5s 等待/50ms 轮询/120s 停滞） */
    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        // 发号用递增替身——固定值会跨键撞 id（builder 第 4 步实战坑：transition 按 id 定位）
        store = new MemoryIdempotentStore(new java.util.concurrent.atomic.AtomicLong()::incrementAndGet,
                clock);
        engine = new IdempotentEngine(store, clock, Duration.ofHours(24),
                Duration.ofMillis(5000), 50, Duration.ofSeconds(120));
        actionRuns.set(0);
    }

    /** 收尾线程池 */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 首个请求认领执行，落 DONE + 载荷 */
    @Test
    void firstGuardExecutesAndStoresDone() {
        IdempotentResult r = engine.guard("", "order-1", act("payload-a"));
        assertThat(r.isExecuted()).isTrue();
        assertThat(r.getPayload()).isEqualTo("payload-a");
        assertThat(actionRuns.get()).isEqualTo(1);
        com.xp.starsite.idempotent.api.IdempotentRecord row = store.find("", "order-1").get();
        assertThat(row.getStatus()).isEqualTo(IdempotentStatus.DONE);
        assertThat(row.getResult()).isEqualTo("payload-a");
    }

    /** 窗口内重复请求回放首次结果——动作零执行 */
    @Test
    void secondGuardReplaysWithoutAction() {
        engine.guard("", "order-1", act("first"));
        clock.advanceSeconds(60); // 窗口内推进（远小于 24h）
        IdempotentResult second = engine.guard("", "order-1", act("second"));
        assertThat(second.isReplayed()).isTrue();
        assertThat(second.getPayload()).isEqualTo("first");
        assertThat(actionRuns.get()).isEqualTo(1);
    }

    /** N 线程同键并发恰一执行：1 EXECUTED + N-1 REPLAYED（等待他方完成后回放） */
    @Test
    void concurrentSameKeyExactlyOneExecution() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                start.countDown();
                try {
                    start.await();
                    IdempotentResult r = engine.guard("order", "batch-1", act("ok"));
                    if (r.isExecuted()) {
                        executed.incrementAndGet();
                    } else if (r.isReplayed()) {
                        replayed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).as("全部线程决出结局").isTrue();
        assertThat(failures.get()).isZero();
        assertThat(executed.get() + replayed.get()).isEqualTo(threads);
        assertThat(executed.get()).as("恰一执行").isEqualTo(1);
        assertThat(actionRuns.get()).as("动作恰跑一次").isEqualTo(1);
    }

    /** 动作抛异常：原异常重抛 + 行落 FAILED（错误摘要留痕） */
    @Test
    void actionFailureMarksFailedAndRethrows() {
        assertThatThrownBy(() -> engine.guard("", "pay-1", counting(() -> {
            throw new IllegalStateException("余额不足");
        }))).isInstanceOf(IllegalStateException.class).hasMessage("余额不足");
        com.xp.starsite.idempotent.api.IdempotentRecord row = store.find("", "pay-1").get();
        assertThat(row.getStatus()).isEqualTo(IdempotentStatus.FAILED);
        assertThat(row.getError()).contains("IllegalStateException").contains("余额不足");
    }

    /** 失败释放键：失败后的新请求重新执行（幂等键与缓存的关键差异） */
    @Test
    void newRequestAfterFailureReexecutes() {
        assertThatThrownBy(() -> engine.guard("", "pay-1", counting(() -> {
            throw new IllegalStateException("first fails");
        })));
        IdempotentResult second = engine.guard("", "pay-1", act("recovered"));
        assertThat(second.isExecuted()).isTrue();
        assertThat(second.getPayload()).isEqualTo("recovered");
        assertThat(actionRuns.get()).isEqualTo(2);
    }

    /** 键记忆窗口耗尽即"键遗忘"：过期 DONE 的重复请求重新执行 */
    @Test
    void expiredDoneKeyForgottenAndReexecutes() {
        engine.guard("", "order-1", act("old"));
        clock.advanceSeconds(Duration.ofHours(25).getSeconds()); // 跨过 24h 窗口
        IdempotentResult second = engine.guard("", "order-1", act("new"));
        assertThat(second.isExecuted()).isTrue();
        assertThat(second.getPayload()).isEqualTo("new");
        assertThat(actionRuns.get()).isEqualTo(2);
    }

    /** 崩溃遗留（PROCESSING 停滞超阈值）：下一个请求回收再执行——自愈 */
    @Test
    void crashedProcessingIsReclaimedAndReexecuted() {
        // 直接经存储模拟认领者崩溃：认领后无人落终态
        store.tryClaim("job", "report-1", clock.now().plusHours(24), "ghost:1");
        clock.advanceSeconds(200); // 超过 120s 停滞阈值
        IdempotentResult r = engine.guard("job", "report-1", act("reclaimed"));
        assertThat(r.isExecuted()).isTrue();
        assertThat(r.getPayload()).isEqualTo("reclaimed");
        assertThat(actionRuns.get()).isEqualTo(1);
        com.xp.starsite.idempotent.api.IdempotentRecord row = store.find("job", "report-1").get();
        assertThat(row.getStatus()).isEqualTo(IdempotentStatus.DONE);
        assertThat(row.getHost()).isNotEqualTo("ghost:1");
    }

    /** 在飞且等待窗口耗尽：CONFLICT（动作零执行） */
    @Test
    void conflictWhenWaitWindowExhausted() {
        IdempotentEngine fastWait = new IdempotentEngine(store, clock, Duration.ofHours(24),
                Duration.ofMillis(150), 50, Duration.ofSeconds(120));
        store.tryClaim("", "busy-1", clock.now().plusHours(24), "other:1"); // 他方在飞（新鲜）
        IdempotentResult r = fastWait.guard("", "busy-1", act("should-not-run"));
        assertThat(r.getKind()).isEqualTo(IdempotentResult.Kind.CONFLICT);
        assertThat(actionRuns.get()).isZero();
    }

    /**
     * P1 回归：等待轮询不占竞态轮次上限——在飞等待需要的轮询轮数远超
     * MAX_ATTEMPTS(16) 时，结局是窗口耗尽的 CONFLICT 而非 IllegalStateException。
     * 修复前：等待轮也计数，~16 轮轮询后慢动作的并发请求先撞异常。
     */
    @Test
    void waitingBeyondAttemptLimitYieldsConflictNotException() {
        // pollMillis=1 + 窗口 200ms → 等待需 ~200 轮轮询（远超 16 轮竞态上限）
        IdempotentEngine manyPollWait = new IdempotentEngine(store, clock, Duration.ofHours(24),
                Duration.ofMillis(200), 1, Duration.ofSeconds(120));
        store.tryClaim("", "busy-many", clock.now().plusHours(24), "other:1"); // 他方在飞（新鲜不判死）
        IdempotentResult r = manyPollWait.guard("", "busy-many", act("should-not-run"));
        assertThat(r.getKind())
                .as("慢动作等待结局应是 CONFLICT（修复前此处是 IllegalStateException）")
                .isEqualTo(IdempotentResult.Kind.CONFLICT);
        assertThat(actionRuns.get()).isZero();
    }

    /** 在飞等待命中完成：重复请求回放他方结果 */
    @Test
    void waitingRequestReplaysAfterFirstCompletes() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<IdempotentResult> waiterResult = new AtomicReference<>();
        // 先到者：认领后阻塞（模拟慢动作）
        Future<?> first = executor.submit(() -> engine.guard("", "slow-1", counting(() -> {
            firstStarted.countDown();
            releaseFirst.await();
            return "slow-result";
        })));
        firstStarted.await(5, TimeUnit.SECONDS);
        // 后到者：等待窗口内轮询
        Future<?> second = executor.submit(() ->
                waiterResult.set(engine.guard("", "slow-1", act("fast-result"))));
        Thread.sleep(200); // 让后到者确实进入等待
        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(waiterResult.get().isReplayed()).isTrue();
        assertThat(waiterResult.get().getPayload()).isEqualTo("slow-result");
        assertThat(actionRuns.get()).isEqualTo(1);
    }

    /** 等待期间目睹他方失败：返回 FAILED 摘要，不自动重跑（防重试风暴） */
    @Test
    void waitingWitnessFailureReturnsSummaryWithoutRerun() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<IdempotentResult> waiterResult = new AtomicReference<>();
        Future<?> first = executor.submit(() -> engine.guard("", "doom-1", counting(() -> {
            firstStarted.countDown();
            releaseFirst.await();
            throw new IllegalStateException("下游超时");
        })));
        firstStarted.await(5, TimeUnit.SECONDS);
        Future<?> second = executor.submit(() ->
                waiterResult.set(engine.guard("", "doom-1", act("never"))));
        Thread.sleep(200);
        releaseFirst.countDown();
        // 先到者以异常收场（guard 重抛动作异常——Future 异常完成）
        assertThat(first).failsWithin(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(waiterResult.get().getKind()).isEqualTo(IdempotentResult.Kind.FAILED);
        assertThat(waiterResult.get().getError()).contains("下游超时");
        assertThat(actionRuns.get()).isEqualTo(1); // 后到者零执行
    }

    /** 作用域隔离：同键不同 scope 各自独立执行（下单/支付同名 orderId） */
    @Test
    void scopeIsolationSameKeyBothExecute() {
        IdempotentResult order = engine.guard("order-create", "1001", act("order-result"));
        IdempotentResult pay = engine.guard("payment", "1001", act("pay-result"));
        assertThat(order.isExecuted()).isTrue();
        assertThat(pay.isExecuted()).isTrue();
        assertThat(actionRuns.get()).isEqualTo(2);
    }

    /** 键空/超长快速失败（幂等键缺失即语义不成立） */
    @Test
    void blankOrOverlongKeyRejected() {
        assertThatThrownBy(() -> engine.guard("", "  ", act("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.guard("", "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk", act("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    /** purgeExpired 清理过期行（语义已死纯卫生；DONE 计数归零） */
    @Test
    void purgeExpiredRemovesExpiredRows() {
        engine.guard("", "k1", act("v1"));
        engine.guard("", "k2", act("v2"));
        clock.advanceSeconds(Duration.ofHours(25).getSeconds());
        long removed = engine.purgeExpired(10);
        assertThat(removed).isEqualTo(2);
        assertThat(store.countByStatus(IdempotentStatus.DONE)).isZero();
        assertThat(store.find("", "k1")).isEmpty();
    }

    /** 过期回收后新代执行：旧代终态被双条件守卫拦截（代际令牌） */
    @Test
    void staleGenerationTerminalWriteDiscarded() {
        // 第一代认领（拿到 expireAt 代际令牌）后停滞
        ClaimOutcome gen1 = store.tryClaim("", "gen-1", clock.now().plusHours(24), "ghost:1");
        clock.advanceSeconds(200); // 判死
        // 第二代经引擎回收再执行并完成
        IdempotentResult gen2 = engine.guard("", "gen-1", act("gen2-result"));
        assertThat(gen2.isExecuted()).isTrue();
        // 第一代幽灵迟到写入：双条件守卫（PROCESSING + 本代 expire_at）拒绝
        boolean ghostWrite = store.complete("", "gen-1",
                gen1.record().getExpireAt(), "ghost-result");
        assertThat(ghostWrite).isFalse();
        // 行仍是第二代的 DONE 结果——未被污染
        assertThat(store.find("", "gen-1").get().getResult()).isEqualTo("gen2-result");
    }

    /** 空结果载荷（void 语义）：DONE 回放为 null 载荷 */
    @Test
    void voidActionReplaysAsNullPayload() {
        IdempotentResult first = engine.guard("", "void-1", act(null));
        assertThat(first.isExecuted()).isTrue();
        assertThat(first.getPayload()).isNull();
        IdempotentResult second = engine.guard("", "void-1", act(null));
        assertThat(second.isReplayed()).isTrue();
        assertThat(second.getPayload()).isNull();
        assertThat(actionRuns.get()).isEqualTo(1);
    }

    /** 多键并行互不干扰（store 视角计数与逐键回放） */
    @Test
    void multipleKeysIndependent() {
        Arrays.asList("a", "b", "c").forEach(k -> engine.guard("", k, act("v-" + k)));
        assertThat(store.countByStatus(IdempotentStatus.DONE)).isEqualTo(3);
        assertThat(engine.guard("", "b", act("v-b")).isReplayed()).isTrue();
    }

    /**
     * 计数动作工厂：固定载荷 + 执行计数（回放断言"动作零执行"的依据）。
     *
     * @param payload 动作返回载荷（null 即 void 语义）
     * @return 动作实例
     */
    private IdempotentAction act(String payload) {
        return counting(() -> payload);
    }

    /**
     * 包装任意动作体：执行计数 + 原体语义。
     *
     * @param body 动作体
     * @return 计数动作
     */
    private IdempotentAction counting(IdempotentAction body) {
        return () -> {
            actionRuns.incrementAndGet();
            return body.run();
        };
    }
}
