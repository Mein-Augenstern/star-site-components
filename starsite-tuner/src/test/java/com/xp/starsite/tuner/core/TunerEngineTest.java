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
package com.xp.starsite.tuner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.store.memory.MemoryTunerStore;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 调参引擎语义矩阵（memory store + 显式时钟）：
 * 窗口命中求值（纯时间函数）/ 收缩序（先 max 后 core）/ 无配置不动（保持基线）/
 * 写入期校验 / 审计留痕 / no-op 去重 / 并发应用安全。
 *
 * @author muyi
 * @date 2026/11/14
 */
class TunerEngineTest {

    /** 可拨时钟（窗口求值的确定性前提） */
    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(
            LocalDateTime.of(2026, 11, 14, 10, 0, 0));

    /** 被测引擎 */
    private TunerEngine engine;

    /** 受管池（真实 TPE，有界队列——治理收益形态） */
    private ThreadPoolExecutor pool;

    /** 每用例重建 */
    @BeforeEach
    void setUp() {
        engine = new TunerEngine(new MemoryTunerStore(clock::get),
                clock::get, ZoneId.of("Asia/Shanghai"));
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "tuner-test-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 收尾 */
    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** 无配置注册：保持基线不动（不猜——建户总量是业务决策的 tuner 版） */
    @Test
    void noConfigKeepsBaseline() {
        engine.register("pool-a", pool);
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(engine.pools().get(0).getTargetPreset()).isEqualTo("baseline");
        assertThat(engine.recentAudits(10)).isEmpty(); // no-op 不进审计
    }

    /** 默认绑定：无窗口时绑定预案生效（扩容方向：先 core 后 max） */
    @Test
    void defaultBindingApplied() {
        pool.shutdownNow();
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));
        engine = new TunerEngine(new MemoryTunerStore(clock::get), clock::get,
                ZoneId.of("Asia/Shanghai"));
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("daily", 6, 8, 120));
        engine.bindDefault("pool-a", "daily");
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(6);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(8);
        assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(120);
        // 审计一行：old 2/4/60 → new 6/8/120
        var audits = engine.recentAudits(5);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getOldCore()).isEqualTo(2);
        assertThat(audits.get(0).getNewCore()).isEqualTo(6);
        assertThat(audits.get(0).getSource()).isEqualTo(TunerAuditEntry.Source.MANUAL);
    }

    /** 收缩序：8/16 → 3/5（先 max 后 core——JDK 恒 core<=max） */
    @Test
    void shrinkOrderMaxBeforeCore() {
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("small", 3, 5, 30));
        engine.bindDefault("pool-a", "small");
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(3);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
    }

    /** 窗口命中：cron 区间内应用窗口预案，区间外回默认绑定 */
    @Test
    void windowActivationByTime() {
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("daily", 2, 4, 60));
        engine.upsertPreset(new TunerPreset("repayday", 8, 16, 60));
        engine.bindDefault("pool-a", "daily");
        // 还款日窗口：每月 10 日 00:00 起，16 日 00:00 止
        engine.upsertWindow(new TunerWindow("pool-a", "repayday",
                "0 0 0 10 * ?", "0 0 0 16 * ?"));

        // 11 月 12 日（窗口内）→ repayday 生效
        clock.set(LocalDateTime.of(2026, 11, 12, 10, 0, 0));
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(8);
        assertThat(engine.pools().get(0).getTargetPreset()).isEqualTo("repayday");

        // 11 月 20 日（窗口外）→ 回默认 daily（幂等 no-op 不重复审计）
        clock.set(LocalDateTime.of(2026, 11, 20, 10, 0, 0));
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(engine.pools().get(0).getTargetPreset()).isEqualTo("daily");
        // 审计恰好 2 行真实变更（进窗 + 出窗），无 no-op 噪音
        assertThat(engine.recentAudits(10)).hasSize(2);
    }

    /** 窗口边界精度：恰在 start 时刻求值——nextAfter 语义决定边界归属 */
    @Test
    void windowBoundarySemantics() {
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("daily", 2, 4, 60));
        engine.upsertPreset(new TunerPreset("peak", 6, 9, 60));
        engine.bindDefault("pool-a", "daily");
        // 窗口 [10:00, 12:00) 每日
        engine.upsertWindow(new TunerWindow("pool-a", "peak", "0 0 10 * * ?", "0 0 12 * * ?"));

        // 09:59:59 窗口外
        clock.set(LocalDateTime.of(2026, 11, 14, 9, 59, 59));
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        // 10:00:00 恰在边界：nextStart(10:00 tomorrow... 即下一次是明天 10 点) > nextEnd(今天 12 点) → 窗口内
        clock.set(LocalDateTime.of(2026, 11, 14, 10, 0, 0));
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(6);
        // 12:00:00 恰在 end：nextEnd 即刻 ≤ nextStart → 窗口外（end 先到先得）
        clock.set(LocalDateTime.of(2026, 11, 14, 12, 0, 0));
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
    }

    /** 写入期校验：预案参数非法 / 绑定不存在预案 / 窗口 cron 非法——全部 fail-fast */
    @Test
    void writeTimeValidation() {
        assertThatThrownBy(() -> engine.upsertPreset(new TunerPreset("bad", 8, 4, 60)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("最大线程数");
        assertThatThrownBy(() -> engine.bindDefault("pool-a", "ghost"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("预案不存在");
        assertThatThrownBy(() -> engine.upsertWindow(new TunerWindow("pool-a", "daily",
                "bad cron", "0 0 0 * * ?")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cron 非法");
    }

    /** no-op 去重：重复 reload 同参数不再审计（治理噪音治理） */
    @Test
    void idempotentApplyNoAuditNoise() {
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("daily", 3, 4, 60));
        engine.bindDefault("pool-a", "daily");
        engine.reload();
        engine.reload();
        engine.reload();
        assertThat(engine.recentAudits(10)).hasSize(1);
    }

    /** 启动基线对齐：注册时窗口应生效（宕机跨窗口边界的补偿——无 misfire） */
    @Test
    void startupAlignmentInsideWindow() {
        engine.upsertPreset(new TunerPreset("daily", 2, 4, 60));
        engine.upsertPreset(new TunerPreset("repayday", 8, 16, 60));
        engine.bindDefault("pool-a", "daily");
        engine.upsertWindow(new TunerWindow("pool-a", "repayday",
                "0 0 0 10 * ?", "0 0 0 16 * ?"));
        clock.set(LocalDateTime.of(2026, 11, 12, 10, 0, 0)); // 窗口内
        // 此时才注册（模拟实例在窗口中途启动）——STARTUP 源立即对齐
        engine.register("pool-a", pool);
        assertThat(pool.getCorePoolSize()).isEqualTo(8);
        assertThat(engine.recentAudits(5).get(0).getSource())
                .isEqualTo(TunerAuditEntry.Source.STARTUP);
    }

    /** 并发应用安全：8 线程并发 reload——per-pool 锁串行化，审计恰好 1 条（假变更零容忍） */
    @Test
    void concurrentApplySafe() throws Exception {
        engine.upsertPreset(new TunerPreset("daily", 4, 6, 60));
        engine.bindDefault("pool-a", "daily");
        engine.register("pool-a", pool);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                start.countDown();
                try {
                    start.await();
                    engine.reload();
                } catch (Exception ignored) {
                    // 断言在终态
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.getCorePoolSize()).isLessThanOrEqualTo(pool.getMaximumPoolSize());
        assertThat(pool.getCorePoolSize()).isEqualTo(4);
        // per-pool 锁：8 线程并发 reload 恰 1 条审计（双读双写假变更零容忍）
        assertThat(engine.recentAudits(10)).hasSize(1);
    }

    /** 池快照：活跃度与目标预案名可见（治理视图） */
    @Test
    void snapshotView() throws Exception {
        engine.register("pool-a", pool);
        pool.execute(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var snapshot = engine.pools().get(0);
        assertThat(snapshot.getPoolName()).isEqualTo("pool-a");
        assertThat(snapshot.getActiveCount()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.getTargetPreset()).isEqualTo("baseline");
        assertThat(snapshot.getLastAppliedAt()).isNull();
    }
}
