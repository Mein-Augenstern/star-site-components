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
package com.xp.starsite.lock.redis;

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.lock.model.LockCoreConfig;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 分布式锁实现：SET NX EX 原子争锁 + Lua 脚本 CAS 释放（防误删他方锁）。
 * <p>
 * 与 DB 锁的取舍：Redis 锁的争锁/释放是内存级操作（亚毫秒），适合高频争锁场景；
 * DB 锁无需额外基础设施。两者语义一致（tryLock/unlock），经 LockClient 统一门面对外暴露。
 * <p>
 * 释放脚本：只有 locked_by = 自己才 DEL——防止实例 A 的锁超时后实例 B 获得，
 * A 醒来误删 B 的锁（经典 RedLock 陷阱）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class RedisLockClient implements LockClient {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(RedisLockClient.class);

    /** 释放锁 Lua 脚本：CAS 比较 locked_by 后 DEL（原子防误删） */
    private static final String UNLOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "return redis.call('DEL', KEYS[1]) else return 0 end";

    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;

    /** key 前缀 */
    private final String keyPrefix;

    /** 持锁者标识 */
    private final String ownerId;

    /** 锁 TTL */
    private final Duration ttl;

    /**
     * 全参构造。
     *
     * @param redisTemplate Redis 字符串模板
     * @param config        组件配置
     */
    public RedisLockClient(StringRedisTemplate redisTemplate, LockCoreConfig config) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = config.getRedisKeyPrefix();
        this.ownerId = config.getOwnerId();
        this.ttl = Duration.ofMillis(config.getTtlMs());
    }

    /**
     * 非阻塞争锁：SET key value NX EX ttl（原子）。
     * 同一持有者重复调用 = 续期（重设 TTL）。
     *
     * @param lockName 锁名
     * @return true 获得锁
     */
    @Override
    public boolean tryLock(String lockName) {
        String key = keyPrefix + lockName;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, ownerId, ttl);
        if (Boolean.TRUE.equals(ok)) {
            log.trace("[starsite-lock] Redis 获得锁: key={}, owner={}", key, ownerId);
            return true;
        }
        // 已存在：如果是自己持的锁，续期（重设 TTL）
        String current = redisTemplate.opsForValue().get(key);
        if (ownerId.equals(current)) {
            redisTemplate.expire(key, ttl);
            return true;
        }
        return false;
    }

    /**
     * 释放锁：Lua CAS 比较后 DEL（原子防误删他方锁）。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        String key = keyPrefix + lockName;
        try {
            Long result = redisTemplate.execute(
                    new DefaultRedisScript<>(UNLOCK_LUA, Long.class),
                    List.of(key), ownerId);
            if (result != null && result > 0) {
                log.info("[starsite-lock] Redis 锁已释放: key={}", key);
            }
        } catch (RuntimeException e) {
            log.warn("[starsite-lock] Redis 释放异常（等 TTL 自动过期）: key={}, err={}", key, e.getMessage());
        }
    }

    /** 确保序列化可比较（防 IDE 警告） */
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyPrefix, ownerId);
    }
}
