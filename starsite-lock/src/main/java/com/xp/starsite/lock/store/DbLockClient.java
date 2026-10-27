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
import com.xp.starsite.lock.client.ReentrantHoldCounter;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 分布式锁实现（互斥语义职责，ShedLock 模式）：
 * 争锁是单条原子 UPDATE（lock_name 唯一键点查），TTL 过期自动释放，不阻塞未获锁实例。
 * <p>
 * 职责边界（单一职责拆分）：
 * <ul>
 *   <li>本类——互斥语义：CAS 争锁/续期、按计数释放；争锁 SQL 异常 fail-open</li>
 *   <li>{@link LockTableProvisioner}——存储就绪：建表与锁行播种（种子 SQL 每锁名一次）</li>
 *   <li>{@link ReentrantHoldCounter}——重入计数：实例内持有层数（归零才真正释放）</li>
 *   <li>starter——装配：ownerId 回填、模式选择、建表触发</li>
 * </ul>
 * SQL 全在与 Mapper 同包的 XML——本类零硬编码 SQL；索引最小集 PK + uk(lock_name)，全点查无扫描。
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

    /** 存储就绪器（建表/播种，争锁路径按需播种） */
    private final LockTableProvisioner provisioner;

    /** 锁表名 */
    private final String tableName;

    /** 持锁者标识 */
    private final String ownerId;

    /** 锁 TTL 毫秒 */
    private final long ttlMs;

    /** 时钟（TTL 到期判定；测试注入假时钟确定性构造过期场景） */
    private final java.time.Clock clock;

    /** 重入持有计数（实例内层数；归零才真正释放 DB 行） */
    private final ReentrantHoldCounter holds = new ReentrantHoldCounter();

    /**
     * 构造（系统时钟）。
     *
     * @param mapper      MyBatis Mapper
     * @param provisioner 存储就绪器（须与本客户端同表名）
     * @param tableName   锁表名（仅字母数字下划线）
     * @param ownerId     持锁者标识
     * @param ttlMs       锁 TTL 毫秒
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, LockTableProvisioner provisioner,
                        String tableName, String ownerId, long ttlMs) {
        this(mapper, provisioner, tableName, ownerId, ttlMs, java.time.Clock.systemDefaultZone());
    }

    /**
     * 全参构造（显式时钟——测试注入假时钟确定性构造过期场景）。
     *
     * @param mapper      MyBatis Mapper
     * @param provisioner 存储就绪器（须与本客户端同表名）
     * @param tableName   锁表名（仅字母数字下划线）
     * @param ownerId     持锁者标识
     * @param ttlMs       锁 TTL 毫秒
     * @param clock       时钟
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, LockTableProvisioner provisioner,
                        String tableName, String ownerId, long ttlMs, java.time.Clock clock) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.provisioner = provisioner;
        this.tableName = tableName;
        this.ownerId = ownerId;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    /**
     * 非阻塞争锁（可重入，分布式安全）：播种（性能提示级缓存）→ 单条 UPDATE 原子抢占
     * （locked_by 为空/已过期/是自己三种命中）；未抢到时校验锁行存活性——
     * 行丢失（外部删行/表重建导致播种记忆陈旧）自动重播种并重试一次，无需重启自愈。
     * 成功或重入递增持有层数；争锁 SQL 异常 fail-open。
     * <p>
     * 正确性不依赖任何 Pod 本地状态：播种缓存只影响 SQL 条数（持有路径 1 条，
     * 竞争路径 2 条），行丢失经"findHolder 校验 + 重播种 + 重试"收敛——
     * 多 Pod 并发自愈时重播种幂等、重试仍走原子 UPDATE，互斥不破。
     * <p>
     * 重入粒度为 ownerId（实例级）：同实例嵌套作用域安全；跨实例同 ownerId 配置视为续期竞争。
     *
     * @param lockName 锁名
     * @return true 获得锁（或重入成功）；false 他方持锁中
     */
    @Override
    public boolean tryLock(String lockName) {
        try {
            provisioner.seedIfNeeded(lockName);
            if (acquireOnce(lockName)) {
                return true;
            }
            // 未抢到：行丢失自愈。findHolder==null 二义（行不存在/无人持有），
            // 幂等重播种 + 重试收敛两种情形——无人持有时重试即抢到，行不存在时补行再抢
            if (provisioner.findHolder(lockName) == null) {
                provisioner.unseed(lockName);
                provisioner.seedIfNeeded(lockName);
                return acquireOnce(lockName);
            }
            return false;
        } catch (RuntimeException e) {
            // 锁不可用不阻断调用方（fail-open——宁可多实例竞争也别停摆）
            log.warn("[starsite-lock] 争锁异常（fail-open 继续）: lock={}, err={}", lockName, e.getMessage());
            return true;
        }
    }

    /**
     * 单次原子抢占（含取钟与层数登记）。
     *
     * @param lockName 锁名
     * @return true 抢到或重入（层数已 +1）
     */
    private boolean acquireOnce(String lockName) {
        // 单次取钟：until 与 now 同基准（两次 now() 会有毫秒级漂移，到期判定失真）
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = now.plusNanos(ttlMs * 1_000_000);
        boolean acquired = mapper.tryAcquire(tableName, lockName,
                ownerId, until, now) > 0;
        if (acquired) {
            log.trace("[starsite-lock] 获得锁: lock={}, owner={}, 持有层数={}",
                    lockName, ownerId, holds.acquire(lockName));
        }
        return acquired;
    }

    /**
     * 释放一层持有（可重入）：计数 &gt; 0 时仅递减（外层仍在临界区，DB 行不动）；
     * 归零（或无计数——防御直释放）才真正释放 DB 行（仅持锁者有效）。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        int remaining = holds.release(lockName);
        if (remaining > 0) {
            // 内层作用域退出：外层仍在临界区，锁不能放（放到 DB 会被他方立即抢走）
            log.trace("[starsite-lock] 内层释放（外层仍持锁）: lock={}, 剩余层数={}", lockName, remaining);
            return;
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
