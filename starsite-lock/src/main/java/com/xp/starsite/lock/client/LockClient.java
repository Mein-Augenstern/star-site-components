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

    /**
     * 非阻塞争锁（成功即获得持有权并刷新 TTL）。
     * 同一实例重复调用 = 重入（TTL 刷新 + 持有层数递增）；他方持锁中返回 false（不阻塞不等待）。
     *
     * @param lockName 锁名（全应用唯一）
     * @return true 获得锁（或重入成功）；false 他方持锁中（跳过本轮）
     */
    boolean tryLock(String lockName);

    /**
     * 释放一层持有（可重入）：持有层数 &gt; 1 时仅递减（外层仍在临界区，存储不动）；
     * 归零才真正释放（仅当前持锁者有效；他方调用无副作用，不抛异常）。
     * 释放后其他实例可立即争锁。
     *
     * @param lockName 锁名
     */
    void unlock(String lockName);
}
