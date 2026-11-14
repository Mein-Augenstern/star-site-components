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
package com.xp.starsite.tuner.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 可变容量队列测试（正确性关键件——两锁算法改造）：容量准入精确性（并发下不超容）/
 * 收缩软水位线（已入队不丢，新 offer 拒绝）/ 扩容唤醒 / TPE 集成（拒绝语义）/ 基础契约。
 *
 * @author muyi
 * @date 2026/11/14
 */
class ResizableCapacityLinkedBlockingQueueTest {

    /** 并发准入精确性：16 线程各 offer 到容量 100——恰 100 成功，size 不超容 */
    @Test
    void concurrentOfferNeverExceedsCapacity() throws Exception {
        ResizableCapacityLinkedBlockingQueue<Integer> queue = new ResizableCapacityLinkedBlockingQueue<Integer>(100);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int base = t * 100;
            new Thread(() -> {
                start.countDown();
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        if (queue.offer(base + i)) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                    // 断言在终态
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(accepted.get()).isEqualTo(100);
        assertThat(queue.size()).isEqualTo(100);
    }

    /** 收缩软水位线：1000→100，已入队 800 不丢（drain 验证），期间新 offer 被拒 */
    @Test
    void shrinkIsSoftWatermark() {
        ResizableCapacityLinkedBlockingQueue<Integer> queue = new ResizableCapacityLinkedBlockingQueue<Integer>(1000);
        for (int i = 0; i < 800; i++) {
            assertThat(queue.offer(i)).isTrue();
        }
        queue.setCapacity(100);
        assertThat(queue.size()).isEqualTo(800); // 已入队不驱逐
        assertThat(queue.offer(9999)).isFalse(); // 新元素拒收
        // 消化 701 个后 size=99 < 新容量——恢复接收（size=100 时恰满，offer 正确被拒）
        List<Integer> drained = new ArrayList<>();
        queue.drainTo(drained, 701);
        assertThat(drained).hasSize(701);
        assertThat(queue.size()).isEqualTo(99);
        assertThat(queue.offer(9999)).isTrue();
    }

    /** 扩容立即可见 + getCapacity/remainingCapacity */
    @Test
    void growImmediatelyVisible() {
        ResizableCapacityLinkedBlockingQueue<Integer> queue = new ResizableCapacityLinkedBlockingQueue<Integer>(10);
        for (int i = 0; i < 10; i++) {
            queue.offer(i);
        }
        assertThat(queue.offer(99)).isFalse();
        assertThat(queue.remainingCapacity()).isZero();
        queue.setCapacity(50);
        assertThat(queue.getCapacity()).isEqualTo(50);
        assertThat(queue.offer(99)).isTrue();
        assertThat(queue.remainingCapacity()).isEqualTo(39);
    }

    /** put/take 往返（阻塞语义）+ take 后腾容量唤醒语义（经 take 驱动） */
    @Test
    void putTakeRoundTrip() throws Exception {
        ResizableCapacityLinkedBlockingQueue<Integer> queue = new ResizableCapacityLinkedBlockingQueue<Integer>(3);
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertThat(queue.take()).isEqualTo(1); // 腾容量——put 若在等待将被唤醒
        assertThat(queue.take()).isEqualTo(2);
        assertThat(queue.poll(100, TimeUnit.MILLISECONDS)).isEqualTo(3);
        assertThat(queue.poll(50, TimeUnit.MILLISECONDS)).isNull(); // 超时语义
    }

    /** 构造/setCapacity 非法容量快速失败 */
    @Test
    void invalidCapacityRejected() {
        assertThatThrownByByDesign(() -> new ResizableCapacityLinkedBlockingQueue<>(0));
        ResizableCapacityLinkedBlockingQueue queue = new ResizableCapacityLinkedBlockingQueue<>(10);
        assertThatThrownByByDesign(() -> queue.setCapacity(0));
        assertThatThrownByByDesign(() -> queue.setCapacity(-5));
        assertThat(queue.getCapacity()).isEqualTo(10); // 非法调整不生效
    }

    /** remove/contains/iterator/clear 基础契约 */
    @Test
    void basicCollectionContract() {
        ResizableCapacityLinkedBlockingQueue<String> queue = new ResizableCapacityLinkedBlockingQueue<String>(10);
        queue.offer("a");
        queue.offer("b");
        queue.offer("c");
        assertThat(queue.contains("b")).isTrue();
        assertThat(queue.contains("z")).isFalse();
        assertThat(queue.remove("b")).isTrue();
        assertThat(queue.contains("b")).isFalse();
        ArrayList seen = new ArrayList<>();
        for (String s : queue) {
            seen.add(s);
        }
        assertThat(seen).containsExactly("a", "c");
        queue.clear();
        assertThat(queue).isEmpty();
    }

    /** TPE 集成：收缩队列 + max 满 → 拒绝语义触发（backpressure 端到端） */
    @Test
    void threadPoolIntegrationRejectsWhenShrunk() throws Exception {
        ResizableCapacityLinkedBlockingQueue<Runnable> queue = new ResizableCapacityLinkedBlockingQueue<Runnable>(2);
        AtomicInteger rejected = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, queue,
                r -> {
                    Thread t = new Thread(r, "tuner-q-test");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        // 6 个长任务：2 core 占满 → 2 扩到 max → 2 入队
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            pool.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 6 任务：t1/t2 占 core，t3/t4 入队（容量 2 满），t5/t6 offer 失败 → TPE 扩线程到 max
        assertThat(pool.getActiveCount()).isEqualTo(4); // core+max 扩容
        assertThat(queue.size()).isEqualTo(2);
        // 收缩队列到 1（软水位线：已入队 2 保留，新 offer 拒）→ 后续任务被 TPE 拒绝
        queue.setCapacity(1);
        try {
            pool.execute(() -> { });
            throw new AssertionError("应触发拒绝");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            rejected.incrementAndGet();
        }
        assertThat(rejected.get()).isEqualTo(1);
        release.countDown();
        pool.shutdownNow();
    }

    /** 断言 helper（异常类型+消息锚点统一） */
    private static void assertThatThrownByByDesign(Runnable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("容量必须为正");
    }
}
