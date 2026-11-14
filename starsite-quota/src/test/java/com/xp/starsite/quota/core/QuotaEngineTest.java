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
package com.xp.starsite.quota.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.api.QuotaClient;
import com.xp.starsite.quota.api.QuotaExceededException;
import com.xp.starsite.quota.api.QuotaNotFoundException;
import com.xp.starsite.quota.api.QuotaStateException;
import com.xp.starsite.quota.store.memory.MemoryQuotaStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 配额引擎语义矩阵（memory store）：五原子操作语义 / 不变式边界 /
 * 异常定责（Exceeded 带可用量 / State 带账面） / init 幂等 / 并发不变式（16 线程恰额满）。
 *
 * @author muyi
 * @date 2026/11/14
 */
class QuotaEngineTest {

    /** 被测引擎（每用例重建） */
    private QuotaClient quota;

    /** 线程池（并发用例） */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 每用例重建 */
    @BeforeEach
    void setUp() {
        quota = new QuotaEngine(new MemoryQuotaStore());
    }

    /** 收尾线程池 */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 建户即零占用零冻结，available=total */
    @Test
    void initZeroBalance() {
        QuotaBalance b = quota.init("credit:A", 1000);
        assertThat(b.getTotal()).isEqualTo(1000);
        assertThat(b.getOccupied()).isZero();
        assertThat(b.getFrozen()).isZero();
        assertThat(b.getAvailable()).isEqualTo(1000);
    }

    /** init 幂等：重复建户保留现量（不重置账面） */
    @Test
    void initIdempotentKeepsBalance() {
        quota.init("credit:A", 1000);
        quota.occupy("credit:A", 300);
        QuotaBalance again = quota.init("credit:A", 9999); // 已存在——现量返回
        assertThat(again.getTotal()).isEqualTo(1000);
        assertThat(again.getOccupied()).isEqualTo(300);
    }

    /** 占用/释放往返：账面精确回到原点 */
    @Test
    void occupyReleaseRoundTrip() {
        quota.init("credit:A", 1000);
        assertThat(quota.occupy("credit:A", 300).getOccupied()).isEqualTo(300);
        QuotaBalance released = quota.release("credit:A", 300);
        assertThat(released.getOccupied()).isZero();
        assertThat(released.getAvailable()).isEqualTo(1000);
    }

    /** 额度不足：Exceeded 异常携带可用量（业务可展示"还可支用多少"） */
    @Test
    void occupyExceededCarriesAvailable() {
        quota.init("credit:A", 1000);
        quota.occupy("credit:A", 800);
        assertThatThrownBy(() -> quota.occupy("credit:A", 300))
                .isInstanceOf(QuotaExceededException.class)
                .hasFieldOrPropertyWithValue("available", 200L)
                .hasFieldOrPropertyWithValue("requested", 300L);
    }

    /** 冻结/解冻：预占语义，解冻回可用池 */
    @Test
    void freezeUnfreezePool() {
        quota.init("credit:A", 1000);
        assertThat(quota.freeze("credit:A", 400).getFrozen()).isEqualTo(400);
        assertThat(quota.balance("credit:A").orElseThrow().getAvailable()).isEqualTo(600);
        QuotaBalance unfrozen = quota.unfreeze("credit:A", 400);
        assertThat(unfrozen.getFrozen()).isZero();
        assertThat(unfrozen.getAvailable()).isEqualTo(1000);
    }

    /** 冻结转占用：单事务换位——frozen 减 occupied 增，available 不变 */
    @Test
    void confirmSwapsFrozenToOccupied() {
        quota.init("credit:A", 1000);
        quota.freeze("credit:A", 500);
        QuotaBalance confirmed = quota.confirm("credit:A", 500);
        assertThat(confirmed.getFrozen()).isZero();
        assertThat(confirmed.getOccupied()).isEqualTo(500);
        assertThat(confirmed.getAvailable()).isEqualTo(500); // 换位不释放回可用池
    }

    /** 占用+冻结共享总量池：两者之和不可越界 */
    @Test
    void occupyAndFreezeSharePool() {
        quota.init("credit:A", 1000);
        quota.occupy("credit:A", 600);
        assertThatThrownBy(() -> quota.freeze("credit:A", 500)) // 600+500>1000
                .isInstanceOf(QuotaExceededException.class)
                .hasFieldOrPropertyWithValue("available", 400L);
        assertThat(quota.freeze("credit:A", 400).getFrozen()).isEqualTo(400); // 恰好可冻结
    }

    /** 释放超量：State 异常（记账错乱信号——防御性拒绝，不扣成负数） */
    @Test
    void releaseOverOccupiedRejected() {
        quota.init("credit:A", 1000);
        quota.occupy("credit:A", 100);
        assertThatThrownBy(() -> quota.release("credit:A", 200))
                .isInstanceOf(QuotaStateException.class)
                .hasMessageContaining("记账错乱");
    }

    /** 解冻/转销超冻结量：State 异常 */
    @Test
    void unfreezeAndConfirmOverFrozenRejected() {
        quota.init("credit:A", 1000);
        quota.freeze("credit:A", 100);
        assertThatThrownBy(() -> quota.unfreeze("credit:A", 200))
                .isInstanceOf(QuotaStateException.class);
        assertThatThrownBy(() -> quota.confirm("credit:A", 200))
                .isInstanceOf(QuotaStateException.class);
    }

    /** 账户不存在：NotFound（先 init 建户——建户总量是业务决策，组件不猜） */
    @Test
    void operationsOnAbsentAccountNotFound() {
        org.assertj.core.api.ThrowableAssert.ThrowingCallable[] actions = {
            () -> quota.occupy("nope", 1), () -> quota.release("nope", 1),
            () -> quota.freeze("nope", 1), () -> quota.unfreeze("nope", 1),
            () -> quota.confirm("nope", 1), () -> quota.adjustTotal("nope", 1)
        };
        for (var action : actions) {
            assertThatThrownBy(action).isInstanceOf(QuotaNotFoundException.class);
        }
        assertThat(quota.balance("nope")).isEmpty();
    }

    /** 提额/降额：降额不可调出负可用量（越界 State，恰好覆盖通过） */
    @Test
    void adjustTotalRaiseAndProtectedLower() {
        quota.init("credit:A", 1000);
        assertThat(quota.adjustTotal("credit:A", 500).getTotal()).isEqualTo(1500); // 提额
        quota.occupy("credit:A", 1000);
        assertThatThrownBy(() -> quota.adjustTotal("credit:A", -600)) // 1500-600<1000 占用
                .isInstanceOf(QuotaStateException.class)
                .hasMessageContaining("降额越界");
        assertThat(quota.adjustTotal("credit:A", -500).getTotal()).isEqualTo(1000); // 恰好覆盖
    }

    /** 参数校验：空键/超长键/负总量/非正量快速失败 */
    @Test
    void argumentValidation() {
        assertThatThrownBy(() -> quota.init(" ", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quota.init("k".repeat(129), 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("128");
        assertThatThrownBy(() -> quota.init("k", -1)).isInstanceOf(IllegalArgumentException.class);
        quota.init("k", 100);
        assertThatThrownBy(() -> quota.occupy("k", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quota.occupy("k", -5)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 并发不变式边界（组件的核心承诺）：总量 1000，16 线程各占用 100——
     * 恰 10 个成功、6 个 Exceeded，最终账面 occupied=1000 且 available=0——
     * 多实例/多线程并发下不超贷的全部证明。
     */
    @Test
    void concurrentOccupyNeverExceedsTotal() throws Exception {
        quota.init("credit:A", 1000);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                start.countDown();
                try {
                    start.await();
                    quota.occupy("credit:A", 100);
                    succeeded.incrementAndGet();
                } catch (QuotaExceededException expected) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    // 非预期异常按失败计——由 done 超时暴露
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(succeeded.get()).as("恰 10 笔成功").isEqualTo(10);
        assertThat(rejected.get()).as("恰 6 笔被不变式拒绝").isEqualTo(6);
        QuotaBalance finalBalance = quota.balance("credit:A").orElseThrow();
        assertThat(finalBalance.getOccupied()).isEqualTo(1000);
        assertThat(finalBalance.getAvailable()).isZero();
    }

    /** 并发混合操作（占用×冻结×释放）后不变式仍恒成立 */
    @Test
    void mixedConcurrencyInvariantHolds() throws Exception {
        quota.init("credit:A", 2000);
        int rounds = 40;
        CountDownLatch done = new CountDownLatch(rounds * 3);
        for (int i = 0; i < rounds; i++) {
            executor.submit(() -> {
                try {
                    quota.occupy("credit:A", 50);
                } catch (QuotaExceededException ignored) {
                    // 边界拒绝是常态
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    quota.freeze("credit:A", 30);
                } catch (QuotaExceededException ignored) {
                    // 边界拒绝是常态
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    quota.release("credit:A", 20); // 无占用可释放时 State——该线程先占后放
                } catch (Exception ignored) {
                    // 混合竞态下 State 是预期态（本轮未占到）
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        QuotaBalance b = quota.balance("credit:A").orElseThrow();
        assertThat(b.getOccupied() + b.getFrozen()).as("不变式：占用+冻结<=总量").isLessThanOrEqualTo(2000);
        assertThat(b.getOccupied()).as("占用非负").isGreaterThanOrEqualTo(0);
        assertThat(b.getFrozen()).as("冻结非负").isGreaterThanOrEqualTo(0);
    }
}
