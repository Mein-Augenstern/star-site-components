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

/**
 * 分布式锁门面（统一对外服务入口）：业务方只面对本接口，
 * 不感知底层是 DB 还是 Redis。
 * <p>
 * 语义：
 * <ul>
 *   <li>{@link #tryLock(String)}——非阻塞争锁，成功返回 true（已获得持有权并自动续期 TTL）</li>
 *   <li>{@link #unlock(String)}——释放锁（仅持锁者有效，他方调用无副作用）</li>
 *   <li><b>可重入（ownerId/实例粒度）</b>：同一实例对同一锁名重复 tryLock = 续期 + 持有层数 +1；
 *       unlock 按层数递减，归零才真正释放——嵌套作用域的内层 finally 不会误放外层的锁</li>
 *   <li>fail-open（DB 形态）：锁存储异常时放行本轮，上层幂等兜底</li>
 * </ul>
 * 线程安全：实现类须保证多线程并发调用安全。
 * <p>
 * 嵌套用法示例（重入保证外层不被内层释放砸掉）：
 * <pre>{@code
 * if (lockClient.tryLock("job")) {
 *     try {
 *         runStep1();                 // step1 内部也可能 tryLock("job") 再 unlock——外层仍持锁
 *     } finally {
 *         lockClient.unlock("job");   // 计数归零才真正释放
 *     }
 * }
 * }</pre>
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface LockClient {

    /** 等待获取形态的轮询重试间隔上限（毫秒）：DB/Redis 无阻塞通知原语，轮询是唯一可移植形态 */
    long WAIT_RETRY_INTERVAL_MS = 100;

    /** 等待重试的退避起步间隔（毫秒）：首轮 10ms 起、逐次翻倍，封顶 {@link #WAIT_RETRY_INTERVAL_MS} */
    long WAIT_BACKOFF_BASE_MS = 10;

    /**
     * 非阻塞争锁（成功即获得持有权并刷新 TTL）。
     * 同一实例重复调用 = 重入（TTL 刷新 + 持有层数递增）；他方持锁中返回 false（不阻塞不等待）。
     *
     * @param lockName 锁名（全应用唯一）
     * @return true 获得锁（或重入成功）；false 他方持锁中（跳过本轮）
     */
    boolean tryLock(String lockName);

    /**
     * 限时等待争锁（支持获取时等待时间）：立即尝试一次，未获得则在等待窗口内轮询重试，
     * 任一次成功即返回 true；窗口耗尽仍无锁返回 false。
     * <p>
     * 语义边界（诚实声明）：
     * <ul>
     *   <li>轮询而非通知——DB 行锁 / Redis 都没有"释放即唤醒"的可移植原语；
     *       重试间隔自适应退避（OBS-102）：首轮 {@value #WAIT_BACKOFF_BASE_MS}ms 起逐次翻倍、
     *       封顶 {@value #WAIT_RETRY_INTERVAL_MS}ms——短等待场景（常见）首次重试快 10 倍，
     *       高争抢下回到 100ms 节奏与负载折中；无排队公平性（每次重试都是全新原子争锁，
     *       先到不一定先得）</li>
     *   <li>等待期间线程让出（sleep）；被中断时恢复中断标记并返回 false（不吞中断信号）</li>
     *   <li>DB 形态的 fail-open 生效于每一次尝试：存储异常的那次尝试直接返回 true 退出</li>
     *   <li>成功后语义与 {@link #tryLock(String)} 完全一致（TTL 刷新 + 重入计数 +1，
     *       配对一次 unlock）</li>
     * </ul>
     *
     * @param lockName  锁名（全应用唯一）
     * @param waitMillis 最长等待毫秒（&le;0 退化为单次非阻塞尝试）
     * @return true 获得锁（或重入成功）；false 等待窗口内未获得
     */
    default boolean tryLock(String lockName, long waitMillis) {
        if (waitMillis <= 0) {
            return tryLock(lockName);
        }
        long deadline = System.currentTimeMillis() + waitMillis;
        int attempt = 0;
        while (true) {
            if (tryLock(lockName)) {
                return true;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            // 自适应退避（OBS-102）：10ms 起步翻倍、封顶 100ms（生产链路压测实证固定 100ms
            // 在 16 线程争抢下 P95≈3×100ms 的量化长尾；短等待首试重试提速 10 倍）
            long backoff = Math.min(WAIT_RETRY_INTERVAL_MS,
                    WAIT_BACKOFF_BASE_MS << Math.min(attempt, 4));
            try {
                Thread.sleep(Math.min(remaining, backoff));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            attempt++;
        }
    }

    /**
     * 释放一层持有（可重入）：持有层数 &gt; 1 时仅递减（外层仍在临界区，存储不动）；
     * 归零才真正释放（仅当前持锁者有效；他方调用无副作用，不抛异常）。
     * 释放后其他实例可立即争锁。
     *
     * @param lockName 锁名
     */
    void unlock(String lockName);
}
