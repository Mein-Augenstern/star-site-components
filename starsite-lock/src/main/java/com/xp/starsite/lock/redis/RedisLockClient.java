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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 分布式锁实现（互斥语义职责）：hash 结构 + Lua 脚本，重入计数存于 Redis——
 * 存储端权威（同 ownerId 跨实例共享同一份计数，分布式写法）。
 * <p>
 * 数据形态：key 为锁，hash field 为 ownerId，value 为持有层数（Redisson 同款）。
 * 三段脚本各自原子：
 * <ul>
 *   <li>争锁/重入——key 不存在则 HSET(owner,1)+PEXPIRE；field 已存在（自己持有）则
 *       HINCRBY +1 并续期；否则 0（他方持有）</li>
 *   <li>按层释放——field 不存在返回 -1（非持有者）；HINCRBY -1 后仍 &gt;0 则续期返回 0
 *       （外层仍持锁，key 不删）；归零则 DEL 返回 1（真正释放）</li>
 * </ul>
 * CAS 语义贯穿：绝不出现"读后写"的两步操作（key 过期瞬间误延他方锁/误删他方锁都是
 * 经典互斥破坏）。与 DB 锁的取舍：Redis 争锁/释放是内存级操作（亚毫秒），适合高频争锁。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class RedisLockClient implements LockClient {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(RedisLockClient.class);

    /**
     * 争锁/重入脚本（原子）：EXISTS=0 首抢建 hash 计 1 层；HEXISTS(owner) 命中重入 +1；
     * 两种命中都刷新 TTL；否则他方持有返回 0。
     */
    private static final String ACQUIRE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then "
            + "redis.call('HSET', KEYS[1], ARGV[1], 1) "
            + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end "
            + "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then "
            + "redis.call('HINCRBY', KEYS[1], ARGV[1], 1) "
            + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end "
            + "return 0";

    /**
     * 按层释放脚本（原子）：非持有者 -1；递减后仍 >0 续期返回 0（外层仍持锁）；
     * 归零 DEL 返回 1（真正释放，任何实例可立即争锁）。
     */
    private static final String RELEASE_LUA =
            "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return -1 end "
            + "local count = redis.call('HINCRBY', KEYS[1], ARGV[1], -1) "
            + "if count > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 0 end "
            + "redis.call('DEL', KEYS[1]) return 1";

    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;

    /** key 前缀 */
    private final String keyPrefix;

    /** 持锁者标识（hash field） */
    private final String ownerId;

    /** 锁 TTL */
    private final java.time.Duration ttl;

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
        this.ttl = java.time.Duration.ofMillis(config.getTtlMs());
    }

    /**
     * 非阻塞争锁（可重入，存储端计数）：单脚本原子完成首抢/重入/续期三态。
     * 重入粒度为 ownerId——同 ownerId 的任意实例（含跨 Pod 嵌套调用）共享
     * Redis hash 里的同一份层数。
     *
     * @param lockName 锁名
     * @return true 获得锁（或重入成功）；false 他方持锁中
     */
    @Override
    public boolean tryLock(String lockName) {
        String key = keyPrefix + lockName;
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(ACQUIRE_LUA, Long.class),
                List.of(key), ownerId, String.valueOf(ttl.toMillis()));
        boolean acquired = result != null && result == 1;
        if (acquired) {
            log.trace("[starsite-lock] Redis 获得锁/重入: key={}, owner={}", key, ownerId);
        }
        return acquired;
    }

    /**
     * 释放一层持有（可重入，存储端原子）：脚本三态——-1 非持有者（无副作用）、
     * 0 内层退出（计数递减，key 保留给外层）、1 归零真正释放。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        String key = keyPrefix + lockName;
        try {
            Long result = redisTemplate.execute(
                    new DefaultRedisScript<>(RELEASE_LUA, Long.class),
                    List.of(key), ownerId, String.valueOf(ttl.toMillis()));
            if (result != null && result == 1) {
                log.info("[starsite-lock] Redis 锁已释放: key={}", key);
            }
        } catch (RuntimeException e) {
            log.warn("[starsite-lock] Redis 释放异常（等 TTL 自动过期）: key={}, err={}", key, e.getMessage());
        }
    }
}
