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
package com.xp.starsite.idempotent.store.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * memory 存储语义测试：认领互斥（并发恰一）/ 回收三条件 / 终态双条件守卫（代际令牌）/
 * 过期清理 / 状态计数——与表形态行为等价的 memory 侧锚点（reviewer 第 5 尺）。
 *
 * @author muyi
 * @date 2026/11/12
 */
class MemoryIdempotentStoreTest {

    /** 假时钟（过期/停滞窗口确定性推进） */
    private static final class FakeClock implements TimeSource {

        /** 当前时刻 */
        volatile LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0, 0);

        /** {@inheritDoc} */
        @Override
        public LocalDateTime now() {
            return now;
        }
    }

    /** 假时钟 */
    private FakeClock clock;

    /** 被测存储 */
    private MemoryIdempotentStore store;

    /** 测试发号 */
    private final AtomicLong ids = new AtomicLong(100);

    /** 建表口径：新鲜 PROCESSING 的 expire_at */
    private static final LocalDateTime EXPIRE = LocalDateTime.of(2026, 11, 13, 10, 0, 0);

    /** 每用例重建 */
    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        store = new MemoryIdempotentStore(ids::incrementAndGet, clock);
    }

    /** 并发认领互斥：16 线程同键恰 1 认领成功 */
    @Test
    void claimAtomicUnderConcurrency() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger claimed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.countDown();
                try {
                    start.await();
                    if (store.tryClaim("", "race", EXPIRE, "h").claimed()) {
                        claimed.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 语义断言在计数上
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(claimed.get()).isEqualTo(1);
    }

    /** 先到认领、后到 duplicate；scope 隔离同键各认领各的 */
    @Test
    void claimThenDuplicateAndScopeIsolation() {
        assertThat(store.tryClaim("order", "k1", EXPIRE, "h1").claimed()).isTrue();
        assertThat(store.tryClaim("order", "k1", EXPIRE, "h2").claimed()).isFalse();
        assertThat(store.tryClaim("payment", "k1", EXPIRE, "h3").claimed()).isTrue();
    }

    /** 缺键 find 为 empty */
    @Test
    void findEmptyForAbsentKey() {
        assertThat(store.find("", "nope")).isEmpty();
    }

    /** FAILED 立即可回收；fresh DONE 与 fresh PROCESSING 不可回收 */
    @Test
    void reclaimConditions() {
        // FAILED 行：claim → fail
        ClaimOutcome failed = store.tryClaim("", "f1", EXPIRE, "h1");
        store.fail("", "f1", failed.record().getExpireAt(), "boom");
        // fresh DONE 行：claim → complete
        ClaimOutcome done = store.tryClaim("", "d1", EXPIRE, "h1");
        store.complete("", "d1", done.record().getExpireAt(), "ok");
        // fresh PROCESSING 行
        store.tryClaim("", "p1", EXPIRE, "h1");

        assertThat(store.tryReclaim("", "f1", EXPIRE, clock.now(),
                clock.now().minusSeconds(120), "h2")).isPresent();
        assertThat(store.tryReclaim("", "d1", EXPIRE, clock.now(),
                clock.now().minusSeconds(120), "h2")).isEmpty();
        assertThat(store.tryReclaim("", "p1", EXPIRE, clock.now(),
                clock.now().minusSeconds(120), "h2")).isEmpty();
    }

    /** 过期 DONE（键遗忘）与停滞 PROCESSING（崩溃遗留）可回收；回收重置行 */
    @Test
    void reclaimExpiredAndStale() {
        // 过期 DONE
        ClaimOutcome done = store.tryClaim("", "e1", clock.now().plusSeconds(10), "h1");
        store.complete("", "e1", done.record().getExpireAt(), "old");
        clock.now = clock.now.plusSeconds(30); // expire_at 已过
        var reclaimed = store.tryReclaim("", "e1", clock.now().plusHours(24), clock.now(),
                clock.now().minusSeconds(120), "h2");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.orElseThrow().getStatus()).isEqualTo(IdempotentStatus.PROCESSING);
        assertThat(reclaimed.orElseThrow().getResult()).isNull();
        assertThat(reclaimed.orElseThrow().getHost()).isEqualTo("h2");

        // 停滞 PROCESSING（gmt_modified 早于阈值）
        ClaimOutcome stale = store.tryClaim("", "s1", clock.now().plusHours(24), "h1");
        clock.now = clock.now.plusSeconds(200);
        assertThat(store.tryReclaim("", "s1", clock.now().plusHours(24), clock.now(),
                clock.now().minusSeconds(120), "h2")).isPresent();
        assertThat(stale).isNotNull();
    }

    /** 终态双条件守卫：换代后旧代 complete/fail 弃写，新代写入成功 */
    @Test
    void terminalGuardRejectsStaleGeneration() {
        ClaimOutcome gen1 = store.tryClaim("", "g1", clock.now().plusHours(1), "h1");
        clock.now = clock.now.plusSeconds(200); // 停滞判死
        LocalDateTime gen2Expire = clock.now.plusHours(1);
        var gen2 = store.tryReclaim("", "g1", gen2Expire, clock.now(),
                clock.now().minusSeconds(120), "h2").orElseThrow();
        // 旧代守卫失败
        assertThat(store.complete("", "g1", gen1.record().getExpireAt(), "ghost"))
                .isFalse();
        assertThat(store.fail("", "g1", gen1.record().getExpireAt(), "ghost"))
                .isFalse();
        // 新代写入成功
        assertThat(store.complete("", "g1", gen2Expire, "fresh")).isTrue();
        var row = store.find("", "g1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(IdempotentStatus.DONE);
        assertThat(row.getResult()).isEqualTo("fresh");
    }

    /** 过期清理只删过期行 */
    @Test
    void purgeExpiredRemovesOnlyExpired() {
        ClaimOutcome keep = store.tryClaim("", "keep", clock.now().plusHours(24), "h");
        store.complete("", "keep", keep.record().getExpireAt(), "v");
        ClaimOutcome gone = store.tryClaim("", "gone", clock.now().plusSeconds(5), "h");
        store.complete("", "gone", gone.record().getExpireAt(), "v");
        clock.now = clock.now.plusSeconds(30);
        assertThat(store.purgeExpired(10, clock.now)).isEqualTo(1);
        assertThat(store.find("", "keep")).isPresent();
        assertThat(store.find("", "gone")).isEmpty();
    }

    /** 按状态计数 */
    @Test
    void countByStatusCounts() {
        ClaimOutcome a = store.tryClaim("", "a", EXPIRE, "h");
        store.complete("", "a", a.record().getExpireAt(), "v");
        ClaimOutcome b = store.tryClaim("", "b", EXPIRE, "h");
        store.fail("", "b", b.record().getExpireAt(), "e");
        store.tryClaim("", "c", EXPIRE, "h");
        assertThat(store.countByStatus(IdempotentStatus.DONE)).isEqualTo(1);
        assertThat(store.countByStatus(IdempotentStatus.FAILED)).isEqualTo(1);
        assertThat(store.countByStatus(IdempotentStatus.PROCESSING)).isEqualTo(1);
    }

    /** id 发号单调（sequence 替身行为锚点） */
    @Test
    void idsMonotonic() {
        long first = store.tryClaim("", "i1", EXPIRE, "h").record().getId();
        long second = store.tryClaim("", "i2", EXPIRE, "h").record().getId();
        assertThat(second).isGreaterThan(first);
    }

    /**
     * 代际令牌精度契约（P3C 巡检修复的锁定测试）：认领写入带纳秒、终态透传秒精度
     * （模拟表形态 DATETIME 截断回读），仍判同代——sameGeneration 统一截断到秒后比较。
     */
    @Test
    void generationTokenPrecisionTolerant() {
        LocalDateTime withNanos = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 123_000_000);
        ClaimOutcome outcome = store.tryClaim("", "prec-1", withNanos, "h1");
        assertThat(outcome.claimed()).isTrue();
        // 秒精度回读（DATETIME 截断语义）→ 仍同代，终态写入成功
        assertThat(store.complete("", "prec-1",
                withNanos.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), "ok")).isTrue();
        // 真跨代（差 1 秒）仍拒绝
        ClaimOutcome gen2 = store.tryClaim("", "prec-2",
                LocalDateTime.of(2026, 11, 14, 10, 0, 0, 500_000_000), "h1");
        assertThat(store.complete("", "prec-2", LocalDateTime.of(2026, 11, 14, 10, 0, 1), "late"))
                .isFalse();
    }
}
