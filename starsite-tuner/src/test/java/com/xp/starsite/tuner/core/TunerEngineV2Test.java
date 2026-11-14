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

import com.xp.starsite.tuner.api.TunerAlarm;
import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.queue.ResizableCapacityLinkedBlockingQueue;
import com.xp.starsite.tuner.store.memory.MemoryTunerStore;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 引擎 V2 语义矩阵（可拨时钟）：队列容量应用（RCLBQ 池生效/普通池 WARN 跳过）/
 * 审批门（DENY 不应用不审计）/ 自适应规则（优先级压过窗口 + 冷却防抖）/
 * 阈值告警（冷却防风暴）/ 审计 retention。
 *
 * @author muyi
 * @date 2026/11/14
 */
class TunerEngineV2Test {

    /** 可拨时钟 */
    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(
            LocalDateTime.of(2026, 11, 14, 10, 0, 0));

    /** 受管池（可变容量队列——V2 完整形态） */
    private ThreadPoolExecutor pool;

    /** 收尾 */
    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** 构造引擎（V2 选项注入） */
    private TunerEngine engine(MemoryTunerStore store, TunerOptions options) {
        return new TunerEngine(store, clock::get, ZoneId.of("Asia/Shanghai"), options);
    }

    /** 队列容量应用：RCLBQ 池按预案切换（扩/缩），审计含 old/new queue */
    @Test
    void queueCapacityAppliedToResizableQueue() {
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ResizableCapacityLinkedBlockingQueue<>(100));
        MemoryTunerStore store = new MemoryTunerStore(clock::get);
        TunerEngine engine = engine(store, TunerOptions.defaults());
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("flood", 4, 8, 60, 500));
        engine.bindDefault("pool-a", "flood");
        engine.reload();
        assertThat(pool.getQueue().size()).isZero();
        assertThat(((ResizableCapacityLinkedBlockingQueue<?>) pool.getQueue()).getCapacity())
                .isEqualTo(500);
        com.xp.starsite.tuner.api.TunerAuditEntry audit = engine.recentAudits(5).get(0);
        assertThat(audit.getOldQueue()).isEqualTo(100);
        assertThat(audit.getNewQueue()).isEqualTo(500);
        // 回程收缩
        engine.upsertPreset(new TunerPreset("daily", 2, 4, 60, 100));
        engine.bindDefault("pool-a", "daily");
        engine.reload();
        assertThat(((ResizableCapacityLinkedBlockingQueue<?>) pool.getQueue()).getCapacity())
                .isEqualTo(100);
    }

    /** 普通队列 + 预案要求 queueCapacity：WARN 跳过（池不受影响，无异常） */
    @Test
    void queueCapacitySkippedForNonResizable() {
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100));
        TunerEngine engine = engine(new MemoryTunerStore(clock::get), TunerOptions.defaults());
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("flood", 3, 4, 60, 500));
        engine.bindDefault("pool-a", "flood");
        engine.reload();
        assertThat(pool.getCorePoolSize()).isEqualTo(3); // 线程参数照常生效
        assertThat(pool.getQueue().size()).isZero();
        // 队列容量不可变（LinkedBlockingQueue 无 setCapacity）——跳过即正确行为
    }

    /** 审批门 DENY：变更不应用、不审计；ALLOW：照常 */
    @Test
    void approvalGateBlocksDeniedChanges() {
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ResizableCapacityLinkedBlockingQueue<>(100));
        AtomicInteger approvals = new AtomicInteger();
        TunerEngine engine = engine(new MemoryTunerStore(clock::get), TunerOptions.builder()
                .approvalHook(request -> {
                    approvals.incrementAndGet();
                    return request.newCore() > 4
                            ? Optional.of("还款日窗口禁改") // DENY 带理由
                            : Optional.empty();
                })
                .build());
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("flood", 8, 16, 60));
        engine.upsertPreset(new TunerPreset("daily", 3, 4, 60));
        engine.bindDefault("pool-a", "flood");
        engine.reload(); // 8>4 → DENY
        assertThat(pool.getCorePoolSize()).isEqualTo(2); // 未应用
        assertThat(engine.recentAudits(5)).isEmpty(); // 拒绝不是变更——不进审计
        engine.bindDefault("pool-a", "daily");
        engine.reload(); // 3<=4 → ALLOW
        assertThat(pool.getCorePoolSize()).isEqualTo(3);
        assertThat(approvals.get()).isEqualTo(2);
        assertThat(engine.recentAudits(5)).hasSize(1);
    }

    /** 自适应规则：命中压过窗口（ADAPTIVE 审计）+ 冷却过期后日历回退（SCHEDULE）+ 再命中（ADAPTIVE） */
    @Test
    void adaptiveRuleWithCooldown() {
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ResizableCapacityLinkedBlockingQueue<>(100));
        java.util.concurrent.atomic.AtomicBoolean emergency =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        TunerEngine engine = engine(new MemoryTunerStore(clock::get), TunerOptions.builder()
                .adaptiveRules(java.util.Arrays.asList(snapshot -> emergency.get()
                        ? Optional.of("protect")
                        : Optional.empty()))
                .adaptiveCooldown(java.time.Duration.ofMinutes(2))
                .build());
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("daily", 2, 4, 60));
        engine.upsertPreset(new TunerPreset("protect", 1, 1, 60));
        engine.bindDefault("pool-a", "daily");

        // T+0：规则命中 → protect（ADAPTIVE）
        engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        assertThat(engine.recentAudits(5).get(0).getSource())
                .isEqualTo(TunerAuditEntry.Source.ADAPTIVE);

        // T+1min：冷却内——规则仍命中但不再评估 → 日历回退 daily（SCHEDULE）
        clock.set(clock.get().plusMinutes(1));
        engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(engine.recentAudits(5).get(0).getSource())
                .isEqualTo(TunerAuditEntry.Source.SCHEDULE);

        // T+2.5min：冷却过期——规则再命中 → protect（ADAPTIVE）
        emergency.set(false); // 规则退出——但先验证冷却过期路径：置回 true 前先跳过
        emergency.set(true);
        clock.set(clock.get().plusMinutes(2));
        engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        assertThat(engine.recentAudits(5).get(0).getSource())
                .isEqualTo(TunerAuditEntry.Source.ADAPTIVE);
        // 恰 3 条真实变更（ADAPTIVE/SCHEDULE/ADAPTIVE）——no-op 零噪音
        List<com.xp.starsite.tuner.api.TunerAuditEntry> audits = engine.recentAudits(10);
        assertThat(audits).hasSize(3);
        assertThat(audits).extracting(TunerAuditEntry::getSource)
                .containsExactly(TunerAuditEntry.Source.ADAPTIVE,
                        TunerAuditEntry.Source.SCHEDULE, TunerAuditEntry.Source.ADAPTIVE);
    }

    /** 阈值告警：活跃度越限触发钩子 + 冷却窗口内不重复（防风暴） */
    @Test
    void alarmHookWithCooldown() {
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ResizableCapacityLinkedBlockingQueue<>(100));
        AtomicReference<TunerAlarm> fired = new AtomicReference<TunerAlarm>();
        AtomicInteger count = new AtomicInteger();
        TunerEngine engine = engine(new MemoryTunerStore(clock::get), TunerOptions.builder()
                .alarmHook(alarm -> {
                    fired.set(alarm);
                    count.incrementAndGet();
                })
                .alarmActiveRatio(0.5) // 活跃/max ≥ 0.5 即告警
                .alarmCooldown(java.time.Duration.ofMinutes(5))
                .build());
        engine.register("pool-a", pool);
        // 活跃 2/max 4 = 0.5 → 触发
        CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        pool.execute(() -> awaitQuiet(release));
        pool.execute(() -> awaitQuiet(release));
        engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        assertThat(count.get()).isEqualTo(1);
        assertThat(fired.get().type()).isEqualTo(TunerAlarm.Type.ACTIVE_RATIO);
        assertThat(fired.get().value()).isEqualTo(0.5);
        // 冷却内：再评估不重复
        clock.set(clock.get().plusMinutes(1));
        engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        assertThat(count.get()).isEqualTo(1);
        // 冷却过：再次触发
        clock.set(clock.get().plusMinutes(6));
        engine.applyAll(TunerAuditEntry.Source.SCHEDULE);
        assertThat(count.get()).isEqualTo(2);
        release.countDown();
    }

    /** 审计 retention：到期行两步清理，未到期保留 */
    @Test
    void auditRetentionPurgesExpiredOnly() {
        pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ResizableCapacityLinkedBlockingQueue<>(100));
        MemoryTunerStore store = new MemoryTunerStore(clock::get);
        TunerEngine engine = engine(store, TunerOptions.builder()
                .auditRetentionDays(7)
                .build());
        engine.register("pool-a", pool);
        engine.upsertPreset(new TunerPreset("daily", 3, 4, 60));
        engine.bindDefault("pool-a", "daily");
        // 8 天前的旧行
        store.appendAudit(new TunerAuditEntry(null, clock.get().minusDays(8), "old-host",
                "pool-a", "old", TunerAuditEntry.Source.MANUAL, 1, 1, 1, 2, 2, 2));
        engine.reload(); // 新变更 + 顺带清理窗口
        assertThat(engine.recentAudits(10)).hasSize(1); // 旧行清掉，仅剩本次变更
        assertThat(engine.recentAudits(10).get(0).getPresetName()).isEqualTo("daily");
    }

    /** 阻塞辅助（测试任务体） */
    private void awaitQuiet(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
