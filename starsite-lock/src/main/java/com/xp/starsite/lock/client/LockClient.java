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
 *   <li>同一实例对同一锁名重复 tryLock = 续期（TTL 刷新）</li>
 * </ul>
 * 线程安全：实现类须保证多线程并发调用安全。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface LockClient {

    /**
     * 非阻塞争锁（成功即获得持有权并刷新 TTL）。
     * 同一持有者重复调用 = 续期（TTL 刷新）；他方持锁中返回 false（不阻塞不等待）。
     *
     * @param lockName 锁名（全应用唯一）
     * @return true 获得锁（可执行）；false 他方持锁中（跳过本轮）
     */
    boolean tryLock(String lockName);

    /**
     * 释放锁（仅当前持锁者有效；他方调用无副作用，不抛异常）。
     * 释放后其他实例可立即争锁。
     *
     * @param lockName 锁名
     */
    void unlock(String lockName);
}
