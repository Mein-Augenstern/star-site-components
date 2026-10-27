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
package com.xp.starsite.lock.store;

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.lock.model.LockCoreConfig;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 分布式锁实现（MyBatis Mapper 版，ShedLock 模式）：
 * 争锁是单条原子 UPDATE，TTL 过期自动释放，不阻塞未获锁实例。
 * <p>
 * 全部 SQL 落在与 {@link ScheduleLockMapper} 同包的 Mapper XML——本类零硬编码 SQL。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class DbLockClient implements LockClient {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(DbLockClient.class);

    /** 表名白名单（防注入——${tableName} 是 MyBatis 字符串替换） */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper */
    private final ScheduleLockMapper mapper;

    /** 锁表名 */
    private final String tableName;

    /** 持锁者标识 */
    private final String ownerId;

    /** 锁 TTL 毫秒 */
    private final long ttlMs;

    /** 主键 id 生成器（starsite-sequence 组件——锁行种子 id 标准来源） */
    private final java.util.function.LongSupplier idGenerator;

    /** 时钟（TTL 到期判定；测试注入假时钟确定性构造过期场景） */
    private final java.time.Clock clock;

    /**
     * 实例内重入计数（锁名 → 持有层数）：同实例同名 tryLock 重入递增，
     * unlock 递减——归零才真正释放 DB 行。嵌套作用域的内层 finally 不会误放外层的锁
     * （互斥权威仍在 DB 的 owner 行，计数只管"本实例还剩几层没出"）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> holds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 已播种的锁名（实例内记忆）：种子行每个锁名只需插一次，稳态下 tryLock 的 SQL 从 2 条降到 1 条 */
    private final java.util.Set<String> seeded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 全参构造（系统时钟）。
     *
     * @param mapper    MyBatis Mapper
     * @param tableName 锁表名（仅字母数字下划线）
     * @param ownerId   持锁者标识
     * @param ttlMs       锁 TTL 毫秒
     * @param idGenerator 主键 id 生成器（starsite-sequence 组件）
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, String tableName, String ownerId, long ttlMs,
                        java.util.function.LongSupplier idGenerator) {
        this(mapper, tableName, ownerId, ttlMs, idGenerator, java.time.Clock.systemDefaultZone());
    }

    /**
     * 全参构造（显式时钟）。
     *
     * @param mapper      MyBatis Mapper
     * @param tableName   锁表名（仅字母数字下划线）
     * @param ownerId     持锁者标识
     * @param ttlMs       锁 TTL 毫秒
     * @param idGenerator 主键 id 生成器（starsite-sequence 组件）
     * @param clock       时钟（TTL 到期判定；测试注入假时钟构造过期场景）
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, String tableName, String ownerId, long ttlMs,
                        java.util.function.LongSupplier idGenerator, java.time.Clock clock) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.tableName = tableName;
        this.ownerId = ownerId;
        this.ttlMs = ttlMs;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 从配置构造。
     *
     * @param mapper ScheduleLockMapper
     * @param config 组件配置
     */
    public DbLockClient(ScheduleLockMapper mapper, LockCoreConfig config, java.util.function.LongSupplier idGenerator) {
        this(mapper, config.getTableName(), config.getOwnerId(), config.getTtlMs(), idGenerator);
    }

    /**
     * 非阻塞争锁（可重入）：先确保锁行存在（每个锁名实例内只播种一次），再原子抢占；
     * 本实例已持有时重入=续期并递增计数。
     * 争锁 SQL 异常时 fail-open（返回 true 继续——宁可竞争不停摆，上层有幂等兜底）。
     * <p>
     * 重入粒度为 ownerId（实例级）：同实例嵌套作用域安全；跨实例同 ownerId 配置视为续期竞争。
     *
     * @param lockName 锁名
     * @return true 获得锁（或重入成功）；false 他方持锁中
     */
    @Override
    public boolean tryLock(String lockName) {
        try {
            // 首次争锁时先补种子行（尽力而为——见 seedLockRowQuietly 的并发语义说明）
            seedLockRowQuietly(lockName);
            // 单次取钟：until 与 now 同基准（两次 now() 会有毫秒级漂移，到期判定失真）
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime until = now.plusNanos(ttlMs * 1_000_000);
            boolean acquired = mapper.tryAcquire(tableName, lockName,
                    ownerId, until, now) > 0;
            if (acquired) {
                holds.computeIfAbsent(lockName, k -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
                log.trace("[starsite-lock] 获得锁: lock={}, owner={}, 持有层数={}",
                        lockName, ownerId, holds.get(lockName));
            }
            return acquired;
        } catch (RuntimeException e) {
            // 锁不可用不阻断调用方（fail-open——宁可多实例竞争也别停摆）
            log.warn("[starsite-lock] 争锁异常（fail-open 继续）: lock={}, err={}", lockName, e.getMessage());
            return true;
        }
    }

    /**
     * 补种子行（尽力而为 + 每锁名一次）：并发首抢时多实例同时播种，输家撞 lock_name 唯一键——
     * 这是正常并发结局而非故障（行已存在正是期望终态），吞掉继续争锁；
     * 真实库故障也会在此抛出，但紧随的 tryAcquire 会以同样方式失败并走 fail-open，语义不变。
     * 互斥权威只在 tryAcquire 的单条 UPDATE——种子不参与判定。
     * <p>
     * 性能：种子 INSERT 只在该锁名首次争锁时执行（实例内记忆），稳态下每次 tryLock 仅
     * 1 条点查 UPDATE（uk_lock_name 命中）。极端运维场景（锁行被外部删除）该锁名将不可
     * 抢占直至重启——以可观测性换 50% 的锁 SQL 削减。
     *
     * @param lockName 锁名
     */
    private void seedLockRowQuietly(String lockName) {
        if (seeded.contains(lockName)) {
            return;
        }
        try {
            mapper.seedLockRow(tableName, idGenerator.getAsLong(), lockName);
            seeded.add(lockName);
        } catch (RuntimeException e) {
            log.debug("[starsite-lock] 种子行已存在或播种失败（继续争锁）: lock={}, err={}",
                    lockName, e.getMessage());
        }
    }

    /**
     * 释放锁（可重入语义）：持有层数 &gt; 1 时仅递减计数（内层作用域退出，外层仍持锁，
     * DB 行不动）；归零（或本实例无计数——防御直释放）才真正释放 DB 行（仅持锁者有效）。
     * 多释放（计数已空再 unlock）不报错——按编程错误防御处理，直接尝试 DB 释放兜底。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        java.util.concurrent.atomic.AtomicInteger count = holds.get(lockName);
        if (count != null) {
            int remaining = count.decrementAndGet();
            if (remaining > 0) {
                // 内层作用域退出：外层仍在临界区，锁不能放（放到 DB 会被他方立即抢走）
                log.trace("[starsite-lock] 内层释放（外层仍持锁）: lock={}, 剩余层数={}", lockName, remaining);
                return;
            }
            holds.remove(lockName, count);
        }
        try {
            int rows = mapper.release(tableName, lockName, ownerId);
            if (rows > 0) {
                log.info("[starsite-lock] 锁已释放: lock={}, owner={}", lockName, ownerId);
            }
        } catch (RuntimeException e) {
            log.warn("[starsite-lock] 释放锁异常（等 TTL 自动过期）: lock={}, err={}", lockName, e.getMessage());
        }
    }
}
