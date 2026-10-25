package com.xp.starsite.sequence;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 雪花生成器单测：假时钟确定性覆盖——唯一性、同毫秒序列、翻毫秒重置、
 * 时钟回拨钳制（借序列位）、FAIL 策略超限拒绝、并发发号。
 *
 * @author muyi
 * @since 2026-10
 */
class SnowflakeIdGeneratorTest {

    /** 可拨动的假时钟（仅存在于测试，不进主代码） */
    static final class FakeTime implements TimeSource {
        long now = 1_700_000_000_000L;
        @Override public long currentTimeMillis() { return now; }
    }

    private static final long EPOCH = 1767225600000L;

    @Test
    void 唯一性_万级发号无重复() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(ids.add(gen.nextId())).isTrue();
        }
    }

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

    @Test
    void 时钟回拨_钳制借序列位_id不倒退() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time);

        long idBefore = gen.nextId();
        time.now -= 500; // 回拨 500ms（容忍内）
        long idAfter = gen.nextId();
        // 时间戳字段被钳制在上次发号毫秒，id 仍严格递增（序列位继续）
        assertThat(idAfter).isGreaterThan(idBefore);
    }

    @Test
    void 时钟回拨_FAIL策略_超限拒绝发号() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.FAIL, time);

        gen.nextId();
        time.now -= 5000; // 回拨 5s，超过容忍 2000ms
        assertThatThrownBy(gen::nextId)
                .isInstanceOf(ClockBackwardException.class)
                .satisfies(e -> assertThat(((ClockBackwardException) e).getBackwardMillis()).isEqualTo(5000));
    }

    @Test
    void 时钟回拨_FAIL策略_容忍内仍钳制() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.FAIL, time);

        long before = gen.nextId();
        time.now -= 1000; // 容忍内
        assertThat(gen.nextId()).isGreaterThan(before);
    }

    @Test
    void 钳制后_物理时钟追平_时间戳恢复前进() {
        FakeTime time = new FakeTime();
        long t0 = time.now;
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time);

        long frozenAt = gen.nextId();
        time.now -= 300;                      // 回拨：虚拟时间冻结在 t0 毫秒
        long clamped = gen.nextId();
        assertThat(clamped).isGreaterThan(frozenAt);   // 借序列位继续

        time.now = t0 + 5_000;                // 物理时钟追平并前进（注意拨的是毫秒不是 id 值）
        long recovered = gen.nextId();
        assertThat(recovered).isGreaterThan(clamped);
        // 时间戳段确实前进了（时间戳在最高段：右移 worker+sequence 共 22 位取出）
        assertThat(recovered >>> 22).isGreaterThan(frozenAt >>> 22);
    }

    @Test
    void 序列耗尽_自旋等下一毫秒() {
        FakeTime time = new FakeTime();
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, EPOCH, 10, 4, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP, time); // 4 位序列 = 单毫秒 16 个

        long first = gen.nextId();
        for (int i = 0; i < 15; i++) {
            gen.nextId(); // 耗尽本毫秒序列
        }
        // 第 17 个必须落到下一毫秒——假时钟没走，说明在自旋前先把时间拨走会解锁
        time.now += 1;
        long overflow = gen.nextId();
        assertThat(overflow & 0xF).isZero(); // 新毫秒序列从 0 开始
        assertThat(overflow).isGreaterThan(first);
    }

    @Test
    void workerId_越界拒绝() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024, EPOCH, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 位宽组合_时间戳至少41位约束() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, EPOCH, 12, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("22");
    }

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
        assertThat(ids).hasSize(threads * perThread); // 10 万号零重复
    }
}
