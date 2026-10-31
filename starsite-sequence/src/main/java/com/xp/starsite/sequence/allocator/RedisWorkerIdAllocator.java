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
package com.xp.starsite.sequence.allocator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis workerId 分配器：INCR 原子取号 + hash 审计——无 MySQL 场景的轻量替代。
 * <p>
 * 语义与 MySQL 版等价：
 * <ul>
 *   <li><b>原子取号</b>：Redis INCR 单命令原子，全平台绝不重复</li>
 *   <li><b>审计行</b>：每个服务的 workerId 记录在 hash（field=服务名, value=workerId）</li>
 *   <li><b>回绕策略</b>：分配值超机器位上限时 RESET（重置计数器重新取号）或 FAIL（拒绝启动）</li>
 *   <li><b>自愈</b>：Redis key 丢失（过期/误删）后 INCR 从 0 重启——workerId 可能与历史重复，
 *       但雪花 id 的全局唯一性靠"时间戳+机器位+序列"三元组，机器位短暂重复在时钟不倒退的前提下安全</li>
 * </ul>
 * <p>
 * 与 MySQL 版的取舍：Redis 亚毫秒取号（MySQL 毫秒级）；但 Redis key 无持久化保证
 * （AOF everysec 仍有丢失窗口），workerId 审计信息不如 MySQL 行可查——适合
 * "已有 Redis、workerId 无严格持久化要求"的团队。
 *
 * @author muyi
 * @date 2026/10/31
 */
public class RedisWorkerIdAllocator implements WorkerIdAllocator {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(RedisWorkerIdAllocator.class);

    /** 全局池计数器 key（INCR 原子取号） */
    private final String poolKey;

    /** 审计 hash key（field=服务名, value=workerId） */
    private final String auditKey;

    /** 服务名（审计 field） */
    private final String serviceName;

    /** 实例标识（审计 value 附带） */
    private final String instanceTag;

    /** 机器位上限 */
    private final long maxWorkerId;

    /** 回绕策略：RESET 重置重取 / FAIL 拒绝 */
    private final MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy;

    /** Redis 模板 */
    private final StringRedisTemplate redis;

    /**
     * 构造 Redis 分配器。
     *
     * @param redis             Redis 字符串模板
     * @param serviceName       服务名（审计 key）
     * @param instanceTag       实例标识
     * @param workerIdBits      机器位宽（决定上限）
     * @param wraparoundPolicy  回绕策略
     */
    public RedisWorkerIdAllocator(StringRedisTemplate redis,
                                  String serviceName, String instanceTag,
                                  int workerIdBits,
                                  MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy) {
        this.redis = redis;
        this.poolKey = "starsite:sequence:worker-id-pool";
        this.auditKey = "starsite:sequence:worker-id-audit";
        this.serviceName = serviceName;
        this.instanceTag = instanceTag;
        this.maxWorkerId = ~(-1L << workerIdBits);
        this.wraparoundPolicy = wraparoundPolicy;
    }

    /**
     * 从 Redis INCR 原子取号 + 记录审计。
     *
     * @return 全局唯一机器位
     * @throws IllegalStateException Redis 不可达或超上限且策略为 FAIL
     */
    @Override
    public long allocate() {
        Long workerId = redis.opsForValue().increment(poolKey);
        if (workerId == null) {
            throw new IllegalStateException("Redis INCR 返回 null（连接异常）");
        }
        log.info("[starsite-sequence] Redis 租约 workerId: service={}, workerId={}, pool={}",
                serviceName, workerId, poolKey);

        if (workerId > maxWorkerId) {
            if (wraparoundPolicy == MysqlWorkerIdAllocator.WraparoundPolicy.FAIL) {
                throw new IllegalStateException("全局池已分配到 " + workerId
                        + " 超过上限 " + maxWorkerId + "，按 FAIL 策略拒绝");
            }
            log.warn("[starsite-sequence] workerId 回绕：{} 超上限 {}，重置重取", workerId, maxWorkerId);
            redis.opsForValue().set(poolKey, "0");
            workerId = redis.opsForValue().increment(poolKey);
            if (workerId == null || workerId > maxWorkerId) {
                throw new IllegalStateException("回绕重取后仍超上限: " + workerId);
            }
        }

        // 审计：hash field=服务名, value="workerId|instanceTag"
        redis.opsForHash().put(auditKey, serviceName, workerId + "|" + instanceTag);
        return workerId;
    }

    /**
     * 描述。
     *
     * @return 形如 "redis-lease(service=svc)"
     */
    @Override
    public String describe() {
        return "redis-lease(service=%s, tag=%s)".formatted(serviceName, instanceTag);
    }
}
