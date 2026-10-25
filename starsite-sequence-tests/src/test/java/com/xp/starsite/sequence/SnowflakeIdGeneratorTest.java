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
package com.xp.starsite.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 雪花生成器单测：假时钟确定性覆盖——唯一性、同毫秒序列、翻毫秒重置、
 * 时钟回拨钳制（借序列位）、FAIL 策略超限拒绝、并发发号。
 *
 * @author muyi
 * @date 2026/10/25
 */
class SnowflakeIdGeneratorTest {

    /**
     * 可拨动的假时钟（测试设施，仅存在于测试，不进主代码）：
     * 直接读写 {@code now} 字段即可模拟时间流逝与时钟回拨，
     * 使回拨/同毫秒/序列耗尽等场景确定性可构造，不依赖真实时间。
     */
    static final class FakeTime implements TimeSource {
        /** 假时钟当前毫秒，测试中直接加减以模拟时间前进/回拨 */
        long now = 1_700_000_000_000L;

        /**
         * 返回假时钟当前毫秒。
         *
         * @return now 字段当前值
         */
        @Override public long currentTimeMillis() { return now; }
    }

    /** 测试用起始纪元毫秒（与默认配置一致的 2026-01-01） */
    private static final long EPOCH = 1767225600000L;

    /** 万级连续发号：默认位宽下 id 集合零重复，验证唯一性基线。 */
    @Test
    void 唯一性_万级发号无重复() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(ids.add(gen.nextId())).isTrue();
        }
    }

    /** 同毫秒内序列 0→1 递增；翻毫秒后序列归零且 id 整体前进。 */
    @Test
    void 同毫秒序列递增_翻毫秒重置() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time);

        long id1 = gen.nextId();
        long id2 = gen.nextId();
        // 同毫秒：序列 0 → 1
        assertThat(id2 - id1).isEqualTo(1);

        time.now += 1;
        long id3 = gen.nextId();
        // 翻毫秒：序列重置为 0（步进 = 下一毫秒的整段偏移）
        long seqMask = 0xFFF;
        assertThat(id3 & seqMask).isZero();
        assertThat(id3).isGreaterThan(id2);
    }

    /** CLAMP 策略：容忍内回拨后虚拟时间冻结、序列位继续，id 仍严格递增不倒退。 */
    @Test
    void 时钟回拨_钳制借序列位_id不倒退() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time);

        long idBefore = gen.nextId();
        // 回拨 500ms（容忍内）
        time.now -= 500;
        long idAfter = gen.nextId();
        // 时间戳字段被钳制在上次发号毫秒，id 仍严格递增（序列位继续）
        assertThat(idAfter).isGreaterThan(idBefore);
    }

    /** FAIL 策略：回拨 5s 超容忍 2s 时抛 ClockBackwardException，且异常携带实际回拨毫秒数。 */
    @Test
    void 时钟回拨_FAIL策略_超限拒绝发号() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.FAIL, time);

        gen.nextId();
        // 回拨 5s，超过容忍 2000ms
        time.now -= 5000;
        assertThatThrownBy(gen::nextId)
                .isInstanceOf(ClockBackwardException.class)
                .satisfies(e -> assertThat(((ClockBackwardException) e).getBackwardMillis()).isEqualTo(5000));
    }

    /** FAIL 策略只拒绝超限回拨：容忍内的正常抖动同样钳制借序列位，不受影响。 */
    @Test
    void 时钟回拨_FAIL策略_容忍内仍钳制() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.FAIL, time);

        long before = gen.nextId();
        // 回拨 1000ms，在容忍 2000ms 内
        time.now -= 1000;
        assertThat(gen.nextId()).isGreaterThan(before);
    }

    /** 钳制不是永久冻结：物理时钟追平并前进后，时间戳段恢复前进（按位段取出验证）。 */
    @Test
    void 钳制后_物理时钟追平_时间戳恢复前进() {
        FakeTime time = new FakeTime();
        long t0 = time.now;
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time);

        long frozenAt = gen.nextId();
        // 回拨 300ms：虚拟时间冻结在 t0 毫秒
        time.now -= 300;
        long clamped = gen.nextId();
        // 借序列位继续
        assertThat(clamped).isGreaterThan(frozenAt);

        // 物理时钟追平并前进（注意拨的是毫秒不是 id 值）
        time.now = t0 + 5_000;
        long recovered = gen.nextId();
        assertThat(recovered).isGreaterThan(clamped);
        // 时间戳段确实前进了（时间戳在最高段：右移 worker+sequence 共 22 位取出）
        assertThat(recovered >>> 22).isGreaterThan(frozenAt >>> 22);
    }

    /** 4 位序列单毫秒 16 个：耗尽后自旋等下一毫秒，拨走 1ms 解锁，新毫秒序列从 0 开始。 */
    @Test
    void 序列耗尽_自旋等下一毫秒() {
        FakeTime time = new FakeTime();
        // 4 位序列 = 单毫秒 16 个
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 4, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time);

        long first = gen.nextId();
        for (int i = 0; i < 15; i++) {
            // 耗尽本毫秒序列
            gen.nextId();
        }
        // 第 17 个必须落到下一毫秒——假时钟没走，说明在自旋前先把时间拨走会解锁
        time.now += 1;
        long overflow = gen.nextId();
        // 新毫秒序列从 0 开始
        assertThat(overflow & 0xF).isZero();
        assertThat(overflow).isGreaterThan(first);
    }

    /** workerId 超位宽上限（1024 > 2^10-1）时构造即抛 IllegalArgumentException 拒绝。 */
    @Test
    void workerId_越界拒绝() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 机器位+序列位之和超过 22（时间戳不足 41 位）时构造即拒绝，错误信息说明约束。 */
    @Test
    void 位宽组合_时间戳至少41位约束() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, EPOCH, 12, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("22");
    }

    /** 8 线程并发共 10 万号：单锁串行发号下全局零重复（用 latch 同步收尾，不 sleep 等待）。 */
    @Test
    void 并发发号_八线程十万号_全局无重复() throws InterruptedException {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        int threads = 8;
        int perThread = 12_500;
        var ids = java.util.Collections.synchronizedSet(new HashSet<Long>());
        var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        ids.add(gen.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 10 万号零重复
        assertThat(ids).hasSize(threads * perThread);
    }
}
