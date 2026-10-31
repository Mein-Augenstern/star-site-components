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
package com.xp.starsite.lock.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 限时等待争锁的自适应退避测试（OBS-102）：接口 default 方法的节奏护栏——
 * 10ms 起步逐次翻倍、封顶 WAIT_RETRY_INTERVAL_MS。
 * 生产链路压测实证：固定 100ms 间隔在 16 线程争抢下 P95≈3×100ms（轮询量化长尾）；
 * 退避后短等待场景首次重试快 10 倍。断言用宽松上界（CI 抖动容错），只锁量级不锁毫秒。
 *
 * @author muyi
 * @date 2026/10/31
 */
class LockClientTimedWaitTest {

    /**
     * 可编程假锁：前 {@code failFirstN} 次 tryLock 返回 false，之后返回 true；
     * 记录尝试次数供断言。
     */
    private static final class StubLockClient implements LockClient {
        /** 前N次争锁失败（模拟他方持有） */
        final int failFirstN;
        /** 已尝试次数 */
        final AtomicInteger attempts = new AtomicInteger();

        StubLockClient(int failFirstN) {
            this.failFirstN = failFirstN;
        }

        @Override
        public boolean tryLock(String lockName) {
            return attempts.incrementAndGet() > failFirstN;
        }

        @Override
        public void unlock(String lockName) {
            // 测试不覆盖释放路径
        }
    }

    /** 第三次尝试即成功：退避序列 10+20=30ms 左右可得手，远快于旧固定间隔的 2×100ms */
    @Test
    void 短等待_自适应退避_快于固定间隔() {
        StubLockClient lock = new StubLockClient(2);
        long t0 = System.currentTimeMillis();
        boolean acquired = lock.tryLock("job", 5_000);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(acquired).isTrue();
        assertThat(lock.attempts.get()).isEqualTo(3);
        // 退避 10+20=30ms（留 4 倍 CI 抖动余量；旧固定间隔此场景需 ≥200ms）
        assertThat(elapsed).as("自适应退避应在 ~30ms 量级得手").isLessThan(120);
    }

    /** 长时间争抢下退避必须封顶：尝试 12 次的总退避 ≤ 10+20+40+80+100×8 = 950ms */
    @Test
    void 长争抢_退避封顶不过载() {
        StubLockClient lock = new StubLockClient(11);
        long t0 = System.currentTimeMillis();
        boolean acquired = lock.tryLock("job", 5_000);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(acquired).isTrue();
        assertThat(lock.attempts.get()).isEqualTo(12);
        // 理论 950ms（+执行开销）；若退避无封顶（10<<11=20s）必然超时失败或远超此界
        assertThat(elapsed).as("退避必须封顶在 100ms").isLessThan(1_600);
    }

    /** 等待窗口耗尽仍无锁：返回 false，且窗口内多次重试（退避生效非单发） */
    @Test
    void 窗口耗尽_返回false() {
        StubLockClient lock = new StubLockClient(Integer.MAX_VALUE);
        boolean acquired = lock.tryLock("job", 150);

        assertThat(acquired).isFalse();
        // 150ms 窗口 + 10/20/40/80 退避 → 至少 3 次重试（固定 100ms 间隔只有 1 次重试机会）
        assertThat(lock.attempts.get()).isGreaterThanOrEqualTo(4);
    }
}
